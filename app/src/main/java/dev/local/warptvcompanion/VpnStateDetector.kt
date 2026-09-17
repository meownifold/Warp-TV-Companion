package dev.local.warptvcompanion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

object VpnStateDetector {
    /** Returns true when Android currently exposes at least one VPN transport. */
    fun isVpnActive(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        Log.d(WarpConstants.LOG_TAG, "VPN state: ${if (active) "connected" else "disconnected"}")
        return active
    }
}
