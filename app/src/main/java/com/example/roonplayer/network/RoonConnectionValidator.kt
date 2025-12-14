package com.example.roonplayer.network

import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.net.*

/**
 * 简化的Roon Core连接验证器
 * 
 * 设计原则：
 * - 简单胜于复杂
 * - 显式胜于隐式  
 * - 单一职责：验证给定IP的Roon Core是否可连接
 */
class RoonConnectionValidator {
    companion object {
        private const val TAG = "RoonConnectionValidator"
        private const val ROON_API_PORT = 9330
        private const val CONNECTION_TIMEOUT_MS = 3000
    }

    /**
     * 连接结果封装
     */
    sealed class ConnectionResult {
        object Success : ConnectionResult()
        data class NetworkError(val message: String) : ConnectionResult()
        data class InvalidCore(val message: String) : ConnectionResult()
        data class Timeout(val message: String) : ConnectionResult()
    }

    /**
     * 验证指定IP和端口的Roon Core连接性
     * 
     * @param ip Roon Core IP地址
     * @param port 端口号，默认为9330
     * @param customTimeout 自定义超时时间(毫秒)，默认使用CONNECTION_TIMEOUT_MS
     * @return 连接验证结果
     */
    suspend fun validateConnection(ip: String, port: Int = ROON_API_PORT, customTimeout: Int = CONNECTION_TIMEOUT_MS): ConnectionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Validating Roon Core connection to: $ip:$port")

        if (!isValidIpAddress(ip)) {
            return@withContext ConnectionResult.InvalidCore("Invalid IP address format: $ip")
        }

        try {
            val address = InetAddress.getByName(ip)
            
            withTimeout(customTimeout.toLong()) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), customTimeout)
                }
            }
            
            Log.i(TAG, "Successfully connected to Roon Core at $ip:$port")
            ConnectionResult.Success
            
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Connection timeout to $ip:$port")
            ConnectionResult.Timeout("Connection timeout after ${customTimeout}ms")
            
        } catch (e: ConnectException) {
            Log.w(TAG, "Connection refused by $ip:$port")
            ConnectionResult.NetworkError("Connection refused - is Roon Core running?")
            
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Unknown host: $ip")
            ConnectionResult.NetworkError("Cannot resolve IP address: $ip")
            
        } catch (e: IOException) {
            Log.w(TAG, "Network error connecting to $ip", e)
            ConnectionResult.NetworkError("Network error: ${e.message}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error validating connection to $ip", e)
            ConnectionResult.InvalidCore("Unexpected error: ${e.message}")
        }
    }

    /**
     * 获取Roon Core WebSocket连接URL
     */
    fun getWebSocketUrl(ip: String): String {
        return "ws://$ip:$ROON_API_PORT/api"
    }

    /**
     * 简单的IP地址格式验证
     */
    private fun isValidIpAddress(ip: String): Boolean {
        if (ip.isBlank()) return false
        
        val parts = ip.split(".")
        if (parts.size != 4) return false
        
        return parts.all { part ->
            try {
                val num = part.toInt()
                num in 0..255
            } catch (e: NumberFormatException) {
                false
            }
        }
    }
}