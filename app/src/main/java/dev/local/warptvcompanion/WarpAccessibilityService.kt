package dev.local.warptvcompanion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Drives the official Cloudflare app through its accessibility tree.
 *
 * Cloudflare reports ACTION_CLICK as successful on Android TV without producing
 * the required touch behavior. Consequently this service deliberately uses only
 * dispatchGesture() for Cloudflare controls.
 */
class WarpAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var state = WarpActionState.IDLE
    private var targetVpnOn = false
    private var stageStartedAt = 0L
    private var wholeActionStartedAt = 0L
    private var fallbackAttempted = false
    private var backAttempts = 0
    private var gestureInFlight = false
    private var disableOptionStableSince = 0L
    private var lastDisableOptionBounds: android.graphics.Rect? = null
    private var retryCount = 0
    private var retryScheduled = false

    override fun onServiceConnected() {
        instance = this
        Log.i(WarpConstants.LOG_TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(WarpConstants.LOG_TAG, "Accessibility service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (state != WarpActionState.IDLE) handler.post(processStep)
    }

    private fun startAction(turnOn: Boolean): Boolean {
        // One action at a time prevents rapid remote presses from toggling WARP
        // repeatedly while Cloudflare is opening.
        if (state != WarpActionState.IDLE) return false
        targetVpnOn = turnOn
        retryCount = 0
        retryScheduled = false
        wholeActionStartedAt = SystemClock.uptimeMillis()
        transition(WarpActionState.OPENING_CLOUDFLARE)
        Log.i(WarpConstants.LOG_TAG, "Cloudflare launch requested; target=${if (turnOn) "on" else "off"}")
        transition(WarpActionState.WAITING_FOR_SWITCH)
        handler.post(processStep)
        return true
    }

    private val processStep = object : Runnable {
        override fun run() {
            if (state == WarpActionState.IDLE) return
            if (SystemClock.uptimeMillis() - wholeActionStartedAt > TOTAL_TIMEOUT_MS) {
                fail("Timed out controlling Cloudflare WARP.")
                return
            }
            when (state) {
                WarpActionState.WAITING_FOR_SWITCH -> processSwitch()
                WarpActionState.WAITING_FOR_DISABLE_DIALOG -> processDisableDialog()
                WarpActionState.WAITING_FOR_VPN_CHANGE -> processVpnChange()
                else -> Unit
            }
            if (state != WarpActionState.IDLE && state != WarpActionState.SUCCESS && state != WarpActionState.ERROR) {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    private fun processSwitch() {
        val node = findWarpSwitch()

        if (node != null) {
            Log.i(WarpConstants.LOG_TAG, "launchSwitch found checked=${node.isChecked}")

            if (node.isChecked == targetVpnOn) {
                transition(WarpActionState.WAITING_FOR_VPN_CHANGE)
                return
            }

            transition(WarpActionState.CLICKING_SWITCH)

            // Do not advance the state until GestureResultCallback confirms that
            // Android completed the asynchronous touch gesture.
            val accepted = tapNode(
                node,
                onCompleted = {
                    handler.post {
                        if (state != WarpActionState.CLICKING_SWITCH) return@post
                        transition(
                            if (targetVpnOn) {
                                WarpActionState.WAITING_FOR_VPN_CHANGE
                            } else {
                                WarpActionState.WAITING_FOR_DISABLE_DIALOG
                            }
                        )
                        handler.post(processStep)
                    }
                },
                onCancelled = {
                    handler.post {
                        if (state == WarpActionState.CLICKING_SWITCH) {
                            scheduleRetry(
                                "WARP switch gesture was cancelled.",
                                WarpActionState.WAITING_FOR_SWITCH
                            )
                        }
                    }
                }
            )

            if (!accepted) {
                scheduleRetry(
                    "dispatchGesture rejected WARP switch tap.",
                    WarpActionState.WAITING_FOR_SWITCH
                )
            }

            return
        }

        if (stageElapsed() > FALLBACK_AFTER_MS && !fallbackAttempted) {
            fallbackAttempted = true

            Log.w(WarpConstants.LOG_TAG, "launchSwitch not found; using known coordinate fallback")

            // Fixed coordinates are a last resort for the verified 1080p TV UI.
            val accepted = tap(
                WarpUiFallback.SWITCH_X,
                WarpUiFallback.SWITCH_Y,
                onCompleted = {
                    handler.post {
                        transition(
                            if (targetVpnOn) {
                                WarpActionState.WAITING_FOR_VPN_CHANGE
                            } else {
                                WarpActionState.WAITING_FOR_DISABLE_DIALOG
                            }
                        )
                        handler.post(processStep)
                    }
                },
                onCancelled = {
                    handler.post {
                        scheduleRetry(
                            "Coordinate gesture fallback was cancelled.",
                            WarpActionState.WAITING_FOR_SWITCH
                        )
                    }
                }
            )

            if (!accepted) {
                scheduleRetry(
                    "Coordinate gesture fallback rejected.",
                    WarpActionState.WAITING_FOR_SWITCH
                )
            }

            return
        }

        if (stageElapsed() > STAGE_TIMEOUT_MS) {
            fail("Cloudflare WARP switch was not found.")
        }
    }

    private fun processDisableDialog() {
        val node = findDisableForeverOption()

        if (node != null) {
            val currentBounds = android.graphics.Rect().also(node::getBoundsInScreen)
            if (currentBounds != lastDisableOptionBounds) {
                // The dialog is still moving or this is the first complete frame.
                // Restart the stability timer and wait for an unchanged layout.
                lastDisableOptionBounds = android.graphics.Rect(currentBounds)
                disableOptionStableSince = SystemClock.uptimeMillis()
                Log.d(WarpConstants.LOG_TAG, "disable option bounds observed: $currentBounds")
                return
            }

            if (SystemClock.uptimeMillis() - disableOptionStableSince < DIALOG_STABLE_MS) return

            Log.i(
                WarpConstants.LOG_TAG,
                "disable forever clickable parent stable; dispatching one tap"
            )
            transition(WarpActionState.CLICKING_DISABLE_FOREVER)

            val accepted = tapDisableForeverNode(
                node,
                onCompleted = {
                    handler.post {
                        if (state == WarpActionState.CLICKING_DISABLE_FOREVER) {
                            beginWaitingForVpnChange()
                        }
                    }
                },
                onCancelled = {
                    handler.post {
                        if (state == WarpActionState.CLICKING_DISABLE_FOREVER) {
                            scheduleRetry(
                                "Disable option gesture was cancelled.",
                                WarpActionState.WAITING_FOR_DISABLE_DIALOG
                            )
                        }
                    }
                }
            )

            if (!accepted) {
                scheduleRetry(
                    "dispatchGesture rejected disable option tap.",
                    WarpActionState.WAITING_FOR_DISABLE_DIALOG
                )
            }

            return
        }

        if (stageElapsed() > FALLBACK_AFTER_MS && !fallbackAttempted) {
            fallbackAttempted = true

            Log.w(WarpConstants.LOG_TAG, "disable option not found; using coordinate fallback")

            val accepted = tap(
                WarpUiFallback.DISABLE_X,
                WarpUiFallback.DISABLE_Y,
                onCompleted = {
                    handler.post {
                        beginWaitingForVpnChange()
                    }
                },
                onCancelled = {
                    handler.post {
                        scheduleRetry(
                            "Disable coordinate gesture fallback was cancelled.",
                            WarpActionState.WAITING_FOR_DISABLE_DIALOG
                        )
                    }
                }
            )

            if (!accepted) {
                scheduleRetry(
                    "Disable coordinate gesture fallback rejected.",
                    WarpActionState.WAITING_FOR_DISABLE_DIALOG
                )
            }

            return
        }

        if (stageElapsed() > STAGE_TIMEOUT_MS) {
            fail("The permanent disable option was not found.")
        }
    }

    private fun processVpnChange() {
        val vpnActive = VpnStateDetector.isVpnActive(this)
        val cloudflareShowsConnected = if (targetVpnOn) isCloudflareConnectedUi() else false
        // A VPN transport appears several seconds before Cloudflare finishes its
        // connection sequence. For ON, require both signals so the app never
        // returns while Cloudflare still says "正在连接".
        val completed = if (targetVpnOn) {
            vpnActive && cloudflareShowsConnected
        } else {
            !vpnActive
        }

        if (completed) {
            Log.i(
                WarpConstants.LOG_TAG,
                if (targetVpnOn) "VPN connected and Cloudflare UI shows 已连接" else "VPN disconnected"
            )
            state = WarpActionState.SUCCESS
            returnToCompanion(true, if (targetVpnOn) "Connected" else "Disconnected")
        } else if (stageElapsed() > RESPONSE_CHECK_MS && shouldRetryForNoResponse()) {
            scheduleRetry(
                "Cloudflare did not react to the previous touch.",
                WarpActionState.WAITING_FOR_SWITCH
            )
        } else if (stageElapsed() > VPN_TIMEOUT_MS) {
            fail(
                if (targetVpnOn) {
                    "Cloudflare did not reach 已连接 in time."
                } else {
                    "VPN did not disconnect in time."
                }
            )
        }
    }

    /**
     * Retry only when the previous gesture produced no observable UI change.
     * Once the switch has changed state, Cloudflare may legitimately spend time
     * connecting or disconnecting, so waiting is safer than tapping again.
     */
    private fun shouldRetryForNoResponse(): Boolean {
        val switch = findWarpSwitch() ?: return false
        return switch.isChecked != targetVpnOn
    }

    /**
     * A rejected gesture injects no touch, and an unchanged switch confirms that
     * a completed gesture had no effect. Both cases can safely use a bounded
     * retry without risking an unintended extra toggle.
     */
    private fun scheduleRetry(reason: String, nextState: WarpActionState) {
        if (retryScheduled || state == WarpActionState.IDLE || state == WarpActionState.ERROR) return
        if (retryCount >= MAX_RETRY_COUNT) {
            fail("$reason Retry limit reached.")
            return
        }

        retryCount++
        retryScheduled = true
        state = WarpActionState.WAITING_TO_RETRY
        handler.removeCallbacks(processStep)
        Log.w(WarpConstants.LOG_TAG, "$reason Retrying in ${RETRY_DELAY_MS / 1_000}s ($retryCount/$MAX_RETRY_COUNT)")
        Toast.makeText(
            this,
            "WARP 操作未响应，${RETRY_DELAY_MS / 1_000} 秒后重试（$retryCount/$MAX_RETRY_COUNT）",
            Toast.LENGTH_LONG
        ).show()

        handler.postDelayed({
            if (state != WarpActionState.WAITING_TO_RETRY) return@postDelayed
            retryScheduled = false
            transition(nextState)
            handler.post(processStep)
        }, RETRY_DELAY_MS)
    }

    private fun isCloudflareConnectedUi(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText(WarpConstants.CONNECTED_TEXT)
            .any { node ->
                node.text?.toString() == WarpConstants.CONNECTED_TEXT ||
                    node.contentDescription?.toString() == WarpConstants.CONNECTED_TEXT
            }
    }

    private fun beginWaitingForVpnChange() {
        if (state != WarpActionState.CLICKING_DISABLE_FOREVER &&
            state != WarpActionState.WAITING_FOR_DISABLE_DIALOG
        ) return
        transition(WarpActionState.WAITING_FOR_VPN_CHANGE)
        handler.post(processStep)
    }

    fun findWarpSwitch(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId(WarpConstants.SWITCH_VIEW_ID)?.firstOrNull()

    fun findDisableForeverOption(): AccessibilityNodeInfo? {
        val textNodes = rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(WarpConstants.DISABLE_FOREVER_TEXT)
            .orEmpty()
        val exact = textNodes.firstOrNull { it.text?.toString() == WarpConstants.DISABLE_FOREVER_TEXT }
        return AccessibilityUtils.findClickableParent(exact)
    }

    private fun tapNode(
        node: AccessibilityNodeInfo,
        onCompleted: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null
    ): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        // Screen bounds avoid assumptions about density and future layout shifts.
        val x = rect.exactCenterX()
        val y = rect.exactCenterY()

        Log.i(
            WarpConstants.LOG_TAG,
            "tapNode id=${node.viewIdResourceName} text=${node.text} " +
                "bounds=$rect center=($x,$y)"
        )

        return tap(x, y, onCompleted, onCancelled)
    }

    /**
     * Taps inside the lower half of the fifth row instead of its exact center.
     * During Cloudflare's vertical dialog animation, a center tap can occasionally
     * cross into the adjacent fourth option. A 72% vertical position leaves a
     * larger safety margin while remaining inside the clickable fifth-row parent.
     */
    private fun tapDisableForeverNode(
        node: AccessibilityNodeInfo,
        onCompleted: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null
    ): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        val x = rect.exactCenterX()
        val y = rect.top + rect.height() * DISABLE_ROW_Y_FRACTION

        Log.i(
            WarpConstants.LOG_TAG,
            "tapDisableForeverNode bounds=$rect target=($x,$y)"
        )

        return tap(x, y, onCompleted, onCancelled)
    }

    private fun tap(
        x: Float,
        y: Float,
        onCompleted: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null
    ): Boolean {
        // Accessibility events can enqueue several processStep calls. This guard
        // guarantees that those callbacks cannot dispatch duplicate touches.
        if (gestureInFlight) {
            Log.e(WarpConstants.LOG_TAG, "dispatchGesture rejected locally: gesture already in flight")
            return false
        }

        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    // A short stroke is important: a long press on the main switch
                    // can outlive the opening animation and land on the Wi-Fi pause
                    // row that appears at the same screen position.
                    TAP_DURATION_MS
                )
            )
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    Log.i(WarpConstants.LOG_TAG, "Gesture COMPLETED at ($x,$y)")
                    onCompleted?.invoke()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    Log.e(WarpConstants.LOG_TAG, "Gesture CANCELLED at ($x,$y)")
                    onCancelled?.invoke()
                }
            },
            null
        )

        gestureInFlight = accepted

        Log.i(WarpConstants.LOG_TAG, "dispatchGesture accepted=$accepted at ($x,$y)")

        return accepted
    }

    private fun transition(newState: WarpActionState) {
        state = newState
        stageStartedAt = SystemClock.uptimeMillis()
        fallbackAttempted = false
        if (newState == WarpActionState.WAITING_FOR_DISABLE_DIALOG) {
            disableOptionStableSince = 0L
            lastDisableOptionBounds = null
        }
        Log.d(WarpConstants.LOG_TAG, "Action state: $newState")
    }

    private fun stageElapsed() = SystemClock.uptimeMillis() - stageStartedAt

    private fun fail(message: String) {
        Log.e(WarpConstants.LOG_TAG, "error: $message")
        state = WarpActionState.ERROR
        returnToCompanion(false, message)
    }

    private fun returnToCompanion(success: Boolean, message: String) {
        handler.removeCallbacks(processStep)
        backAttempts = 0
        performBackAndFinish(success, message)
    }

    private fun performBackAndFinish(success: Boolean, message: String) {
        if (backAttempts < 2) {
            backAttempts++
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (backAttempts == 1) {
                handler.postDelayed({
                    val activePackage = rootInActiveWindow?.packageName?.toString()
                    if (activePackage == WarpConstants.CLOUDFLARE_PACKAGE) {
                        performBackAndFinish(success, message)
                    } else {
                        finishAction(success, message)
                    }
                }, 350)
                return
            }
        }
        finishAction(success, message)
    }

    private fun finishAction(success: Boolean, message: String) {
        sendBroadcast(Intent(WarpConstants.RESULT_ACTION).apply {
            setPackage(packageName)
            putExtra(WarpConstants.EXTRA_SUCCESS, success)
            putExtra(WarpConstants.EXTRA_MESSAGE, message)
        })
        state = WarpActionState.IDLE
    }

    companion object {
        private const val POLL_MS = 200L
        private const val TAP_DURATION_MS = 30L
        // Require an unchanged fifth-row rectangle for this long before the one
        // and only tap. This starts when the row is actually visible, not when the
        // switch gesture finishes.
        private const val DIALOG_STABLE_MS = 600L
        private const val DISABLE_ROW_Y_FRACTION = 0.72f
        private const val FALLBACK_AFTER_MS = 2_500L
        private const val STAGE_TIMEOUT_MS = 5_000L
        private const val VPN_TIMEOUT_MS = 30_000L
        private const val RESPONSE_CHECK_MS = 5_000L
        private const val RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_COUNT = 3
        private const val TOTAL_TIMEOUT_MS = 75_000L

        @Volatile private var instance: WarpAccessibilityService? = null

        fun beginAction(turnOn: Boolean): Boolean = instance?.startAction(turnOn) == true
    }
}
