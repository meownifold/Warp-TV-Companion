package dev.local.warptvcompanion

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var messageText: TextView
    private lateinit var toggleButton: Button
    private lateinit var settingsButton: Button
    private var operationRunning = false

    // The accessibility service performs the action in Cloudflare's window and
    // reports the final result after returning from that app.
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            operationRunning = false
            val success = intent?.getBooleanExtra(WarpConstants.EXTRA_SUCCESS, false) == true
            val message = intent?.getStringExtra(WarpConstants.EXTRA_MESSAGE).orEmpty()
            refreshUi(if (success) "" else message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        messageText = findViewById(R.id.messageText)
        toggleButton = findViewById(R.id.toggleButton)
        settingsButton = findViewById(R.id.settingsButton)

        toggleButton.setOnClickListener { requestToggle() }
        settingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        toggleButton.nextFocusDownId = R.id.settingsButton
        settingsButton.nextFocusUpId = R.id.toggleButton
        toggleButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, IntentFilter(WarpConstants.RESULT_ACTION), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(resultReceiver, IntentFilter(WarpConstants.RESULT_ACTION))
        }
    }

    override fun onStop() {
        unregisterReceiver(resultReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun requestToggle() {
        // Ignore repeated DPAD_CENTER presses until the current state-machine run
        // succeeds or times out.
        if (operationRunning) return
        if (!AccessibilityUtils.isServiceEnabled(this)) {
            refreshUi("Accessibility service is disabled. Enable WARP TV Companion in Accessibility Settings.")
            settingsButton.requestFocus()
            return
        }
        // Resolve the launcher activity dynamically. Cloudflare may rename its
        // activities between releases, so no activity class is hard-coded.
        val launchIntent = packageManager.getLaunchIntentForPackage(WarpConstants.CLOUDFLARE_PACKAGE)
        if (launchIntent == null) {
            refreshUi("Cloudflare WARP is not installed.")
            return
        }

        val turnOn = !VpnStateDetector.isVpnActive(this)
        val accepted = WarpAccessibilityService.beginAction(turnOn)
        if (!accepted) {
            refreshUi("Accessibility service is not ready. Re-enable it in Accessibility Settings.")
            return
        }
        operationRunning = true
        statusText.text = if (turnOn) "◌ Connecting…" else "◌ Disconnecting…"
        toggleButton.isEnabled = false
        messageText.text = "Opening Cloudflare WARP…"
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(launchIntent)
    }

    private fun refreshUi(error: String = "") {
        if (operationRunning) return
        // The utility intentionally treats any active VPN transport as connected.
        // It is designed for a dedicated TV where WARP is the only VPN provider.
        val connected = VpnStateDetector.isVpnActive(this)
        statusText.text = if (connected) "● Connected" else "○ Disconnected"
        statusText.setTextColor(Color.parseColor(if (connected) "#55D187" else "#AEB7C4"))
        toggleButton.text = if (connected) "Turn Off" else "Turn On"
        toggleButton.isEnabled = true
        val accessibilityEnabled = AccessibilityUtils.isServiceEnabled(this)
        settingsButton.visibility = if (accessibilityEnabled) View.GONE else View.VISIBLE
        messageText.text = when {
            error.isNotEmpty() -> error
            !accessibilityEnabled -> "Accessibility permission required. Enable WARP TV Companion in Accessibility Settings."
            else -> ""
        }
    }
}
