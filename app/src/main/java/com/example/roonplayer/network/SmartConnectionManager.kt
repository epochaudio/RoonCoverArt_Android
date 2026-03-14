package com.example.roonplayer.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.min

class SmartConnectionManager(
    context: Context,
    private val connectionValidator: RoonConnectionValidator,
    private val defaultPort: Int,
    private val maxRetryAttempts: Int,
    private val initialRetryDelayMs: Long,
    private val maxRetryDelayMs: Long,
    private val networkReadyTimeoutMs: Long,
    networkReadyPollIntervalMs: Long,
    networkConnectivityCheckTimeoutMs: Int,
    networkTestHost: String,
    networkTestPort: Int
) {
    companion object {
        private const val TAG = "SmartConnectionManager"
    }

    sealed class ConnectionResult {
        object Success : ConnectionResult()
        data class Failed(val error: String, val canRetry: Boolean = true) : ConnectionResult()
        data class NetworkNotReady(val message: String) : ConnectionResult()
    }

    private val networkDetector = NetworkReadinessDetector(
        context = context,
        networkReadyPollIntervalMs = networkReadyPollIntervalMs,
        connectivityCheckTimeoutMs = networkConnectivityCheckTimeoutMs,
        dnsTestHost = networkTestHost,
        dnsTestPort = networkTestPort
    )

    suspend fun connectWithSmartRetry(
        ip: String, 
        port: Int = defaultPort,
        onStatusUpdate: (String) -> Unit = {}
    ): ConnectionResult = withContext(Dispatchers.IO) {
        
        onStatusUpdate("Checking network connection...")
        Log.i(TAG, "Starting smart connect to $ip:$port")

        val networkState = networkDetector.waitForNetworkReady(networkReadyTimeoutMs)
        when (networkState) {
            is NetworkReadinessDetector.NetworkState.NotAvailable -> {
                onStatusUpdate("Network unavailable. Please check your connection.")
                return@withContext ConnectionResult.NetworkNotReady("Network is unavailable")
            }
            is NetworkReadinessDetector.NetworkState.Error -> {
                onStatusUpdate("Network check failed: ${networkState.message}")
                return@withContext ConnectionResult.NetworkNotReady(networkState.message)
            }
            is NetworkReadinessDetector.NetworkState.Connecting -> {
                onStatusUpdate("Network connecting, waiting...")
            }
            is NetworkReadinessDetector.NetworkState.Available -> {
                onStatusUpdate("Network ready. Connecting...")
            }
        }

        var lastError: String = ""
        var retryDelay = initialRetryDelayMs

        for (attempt in 1..maxRetryAttempts) {
            onStatusUpdate("Connecting... (attempt $attempt/$maxRetryAttempts)")
            Log.d(TAG, "Connection attempt $attempt/$maxRetryAttempts to $ip:$port")

            when (val result = connectionValidator.validateConnection(ip, port)) {
                is RoonConnectionValidator.ConnectionResult.Success -> {
                    onStatusUpdate("Connected.")
                    Log.i(TAG, "Successfully connected to $ip:$port")
                    return@withContext ConnectionResult.Success
                }
                
                is RoonConnectionValidator.ConnectionResult.NetworkError -> {
                    lastError = result.message
                    Log.w(TAG, "Network error (attempt $attempt): $lastError")
                    
                    if (attempt < maxRetryAttempts) {
                        onStatusUpdate("Connection failed. Retrying in ${retryDelay / 1000}s...")
                        delay(retryDelay)
                        retryDelay = min(retryDelay * 2, maxRetryDelayMs)
                    }
                }
                
                is RoonConnectionValidator.ConnectionResult.Timeout -> {
                    lastError = result.message
                    Log.w(TAG, "Connection timeout (attempt $attempt): $lastError")
                    
                    if (attempt < maxRetryAttempts) {
                        onStatusUpdate("Connection timed out. Retrying in ${retryDelay / 1000}s...")
                        delay(retryDelay)
                        retryDelay = min(retryDelay * 2, maxRetryDelayMs)
                    }
                }
                
                is RoonConnectionValidator.ConnectionResult.InvalidCore -> {
                    lastError = result.message
                    Log.e(TAG, "Invalid Roon Core: $lastError")
                    onStatusUpdate("Connection failed: $lastError")
                    return@withContext ConnectionResult.Failed(lastError, canRetry = false)
                }
            }
        }

        onStatusUpdate("Connection failed. Max retries reached.")
        Log.e(TAG, "Connection failed. All retries exhausted. Last error: $lastError")
        return@withContext ConnectionResult.Failed("Connection failed: $lastError", canRetry = true)
    }

    fun registerNetworkMonitoring(onNetworkChange: (NetworkReadinessDetector.NetworkState) -> Unit) {
        networkDetector.registerNetworkCallback(onNetworkChange)
    }

    fun unregisterNetworkMonitoring() {
        networkDetector.unregisterNetworkCallback()
    }

    fun cleanup() {
        networkDetector.cleanup()
    }
}
