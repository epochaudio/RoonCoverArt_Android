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
        
        onStatusUpdate("检测网络连接状态...")
        Log.i(TAG, "开始智能连接到 $ip:$port")

        val networkState = networkDetector.waitForNetworkReady(networkReadyTimeoutMs)
        when (networkState) {
            is NetworkReadinessDetector.NetworkState.NotAvailable -> {
                onStatusUpdate("网络不可用，请检查网络连接")
                return@withContext ConnectionResult.NetworkNotReady("网络连接不可用")
            }
            is NetworkReadinessDetector.NetworkState.Error -> {
                onStatusUpdate("网络检测失败: ${networkState.message}")
                return@withContext ConnectionResult.NetworkNotReady(networkState.message)
            }
            is NetworkReadinessDetector.NetworkState.Connecting -> {
                onStatusUpdate("网络连接中，继续等待...")
            }
            is NetworkReadinessDetector.NetworkState.Available -> {
                onStatusUpdate("网络已就绪，开始连接...")
            }
        }

        var lastError: String = ""
        var retryDelay = initialRetryDelayMs

        for (attempt in 1..maxRetryAttempts) {
            onStatusUpdate("正在连接... (尝试 $attempt/$maxRetryAttempts)")
            Log.d(TAG, "连接尝试 $attempt/$maxRetryAttempts 到 $ip:$port")

            when (val result = connectionValidator.validateConnection(ip, port)) {
                is RoonConnectionValidator.ConnectionResult.Success -> {
                    onStatusUpdate("连接成功！")
                    Log.i(TAG, "成功连接到 $ip:$port")
                    return@withContext ConnectionResult.Success
                }
                
                is RoonConnectionValidator.ConnectionResult.NetworkError -> {
                    lastError = result.message
                    Log.w(TAG, "网络错误 (尝试 $attempt): $lastError")
                    
                    if (attempt < maxRetryAttempts) {
                        onStatusUpdate("连接失败，${retryDelay/1000}秒后重试...")
                        delay(retryDelay)
                        retryDelay = min(retryDelay * 2, maxRetryDelayMs)
                    }
                }
                
                is RoonConnectionValidator.ConnectionResult.Timeout -> {
                    lastError = result.message
                    Log.w(TAG, "连接超时 (尝试 $attempt): $lastError")
                    
                    if (attempt < maxRetryAttempts) {
                        onStatusUpdate("连接超时，${retryDelay/1000}秒后重试...")
                        delay(retryDelay)
                        retryDelay = min(retryDelay * 2, maxRetryDelayMs)
                    }
                }
                
                is RoonConnectionValidator.ConnectionResult.InvalidCore -> {
                    lastError = result.message
                    Log.e(TAG, "无效的Roon Core: $lastError")
                    onStatusUpdate("连接失败: $lastError")
                    return@withContext ConnectionResult.Failed(lastError, canRetry = false)
                }
            }
        }

        onStatusUpdate("连接失败，已达到最大重试次数")
        Log.e(TAG, "连接失败，已用尽所有重试机会。最后错误: $lastError")
        return@withContext ConnectionResult.Failed("连接失败: $lastError", canRetry = true)
    }

    fun registerNetworkMonitoring(onNetworkChange: (NetworkReadinessDetector.NetworkState) -> Unit) {
        networkDetector.registerNetworkCallback(onNetworkChange)
    }

    fun unregisterNetworkMonitoring() {
        networkDetector.unregisterNetworkCallback()
    }
}
