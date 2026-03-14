package com.example.roonplayer.network

import android.content.Context
import android.net.*
import android.util.Log
import kotlinx.coroutines.*
import java.net.Socket
import java.net.InetSocketAddress

class NetworkReadinessDetector(
    context: Context,
    private val networkReadyPollIntervalMs: Long,
    private val connectivityCheckTimeoutMs: Int,
    private val dnsTestHost: String,
    private val dnsTestPort: Int
) {
    companion object {
        private const val TAG = "NetworkReadinessDetector"
    }

    sealed class NetworkState {
        object NotAvailable : NetworkState()
        object Connecting : NetworkState()
        object Available : NetworkState()
        data class Error(val message: String) : NetworkState()
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun waitForNetworkReady(timeoutMs: Long): NetworkState = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting network readiness check")

        val currentState = getCurrentNetworkState()
        if (currentState is NetworkState.Available) {
            Log.i(TAG, "Network is ready")
            return@withContext currentState
        }

        return@withContext try {
            withTimeout(timeoutMs) {
                while (true) {
                    val state = getCurrentNetworkState()
                    when (state) {
                        is NetworkState.Available -> {
                            Log.i(TAG, "Network check complete: ready")
                            return@withTimeout state
                        }
                        is NetworkState.NotAvailable -> {
                            Log.d(TAG, "Network unavailable, waiting...")
                        }
                        is NetworkState.Connecting -> {
                            Log.d(TAG, "Network connecting, waiting...")
                        }
                        is NetworkState.Error -> {
                            Log.w(TAG, "Network check error: ${state.message}")
                        }
                    }
                    delay(networkReadyPollIntervalMs)
                }
                @Suppress("UNREACHABLE_CODE")
                NetworkState.Error("Network readiness loop exited unexpectedly")
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Network readiness check timed out")
            NetworkState.Error("Network check timed out. Please check your network connection.")
        }
    }

    private suspend fun getCurrentNetworkState(): NetworkState = withContext(Dispatchers.IO) {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                return@withContext NetworkState.NotAvailable
            }

            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (networkCapabilities == null) {
                return@withContext NetworkState.NotAvailable
            }

            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return@withContext NetworkState.Connecting
            }

            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                if (isConnectivityTestPassed()) {
                    return@withContext NetworkState.Available
                }
                return@withContext NetworkState.Connecting
            }

            return@withContext NetworkState.Available

        } catch (e: Exception) {
            Log.e(TAG, "Error while getting network state", e)
            return@withContext NetworkState.Error("Failed to check network state: ${e.message}")
        }
    }

    private suspend fun isConnectivityTestPassed(): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeout(connectivityCheckTimeoutMs.toLong()) {
                val socket = Socket()
                socket.connect(InetSocketAddress(dnsTestHost, dnsTestPort), connectivityCheckTimeoutMs)
                socket.close()
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connectivity test failed: ${e.message}")
            false
        }
    }

    fun registerNetworkCallback(onNetworkChange: (NetworkState) -> Unit) {
        check(callbackScope.isActive) { "NetworkReadinessDetector has been cleaned up" }

        // 先清掉旧回调和旧任务，避免重复注册导致状态通知重入。
        unregisterNetworkCallback()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available")
                callbackScope.launch {
                    val state = getCurrentNetworkState()
                    onNetworkChange(state)
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network connection lost")
                onNetworkChange(NetworkState.NotAvailable)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.d(TAG, "Network capabilities changed")
                callbackScope.launch {
                    val state = getCurrentNetworkState()
                    onNetworkChange(state)
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    fun unregisterNetworkCallback() {
        callbackScope.coroutineContext.cancelChildren()
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Network callback was already unregistered: ${e.message}")
            }
            networkCallback = null
        }
    }

    fun cleanup() {
        unregisterNetworkCallback()
        callbackScope.cancel()
    }
}
