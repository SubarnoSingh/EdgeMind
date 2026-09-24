package com.example.EdgeMemo.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.EdgeMemo.core.connectivity.ConnectivityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real connectivity check via `ConnectivityManager`: online only when an active
 * network exists AND it claims the INTERNET capability. No faked status.
 */
class AndroidConnectivityMonitor(
    private val context: Context,
) : ConnectivityMonitor {

    override suspend fun isOnline(): Boolean = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
            ?: return@withContext false
        val network = manager.activeNetwork ?: return@withContext false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@withContext false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}