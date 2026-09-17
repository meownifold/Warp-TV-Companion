package dev.local.warptvcompanion

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityUtils {
    /**
     * Checks the secure setting instead of relying only on the service singleton.
     * The service process can take a moment to reconnect after an APK update.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, WarpAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        // Cloudflare's option label is a non-clickable TextView. Its nearest
        // clickable ancestor represents the complete row and is the tap target.
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }
}
