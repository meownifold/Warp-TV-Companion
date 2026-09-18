package dev.local.warptvcompanion

object WarpConstants {
    const val LOG_TAG = "WarpTV"
    const val CLOUDFLARE_PACKAGE = "com.cloudflare.onedotonedotonedotone"
    const val SWITCH_VIEW_ID = "$CLOUDFLARE_PACKAGE:id/launchSwitch"
    const val DISABLE_FOREVER_TEXT = "直到我重新开启为止"
    const val CONNECTED_TEXT = "已连接"
    const val RESULT_ACTION = "dev.local.warptvcompanion.WARP_ACTION_RESULT"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_MESSAGE = "message"
}

/**
 * Coordinates verified on a 1920x1080 Sony BRAVIA UI. They are used only when
 * Cloudflare's accessibility nodes cannot be found; node-derived centers are
 * preferred during normal operation.
 */
object WarpUiFallback {
    const val SWITCH_X = 960f
    const val SWITCH_Y = 538f
    const val DISABLE_X = 960f
    // Aim below the row center to stay well clear of the fourth/fifth-row boundary.
    const val DISABLE_Y = 752f
}

/** States shared by the ON and OFF automation flows. */
enum class WarpActionState {
    IDLE,
    OPENING_CLOUDFLARE,
    WAITING_FOR_SWITCH,
    CLICKING_SWITCH,
    WAITING_FOR_DISABLE_DIALOG,
    CLICKING_DISABLE_FOREVER,
    WAITING_FOR_VPN_CHANGE,
    WAITING_TO_RETRY,
    SUCCESS,
    ERROR
}
