package com.example.roonplayer.network

import android.util.Log
import kotlinx.coroutines.*

/**
 * 简化的连接辅助类
 * 
 * 用途：替换复杂的发现和连接逻辑
 * 原则：用户提供IP，直接连接到配置注入的默认端口
 */
class SimplifiedConnectionHelper(
    private val connectionValidator: RoonConnectionValidator,
    private val defaultPort: Int
) {
    companion object {
        private const val TAG = "SimplifiedConnectionHelper"
    }

    /**
     * 简化的连接流程
     * 
     * @param userInput 用户输入的IP地址或IP:端口
     * @return Pair<主机, 端口> 或 null 如果无效
     */
    suspend fun validateAndGetConnectionInfo(userInput: String): Pair<String, Int>? {
        if (userInput.isBlank()) {
            Log.w(TAG, "Empty input provided")
            return null
        }

        val (host, port) = parseHostAndPort(userInput)
        
        // 为什么在这里统一补默认端口：
        // 用户输入允许省略端口，但连接验证必须是完整目标，避免调用方重复兜底。
        val finalPort = if (port > 0) port else defaultPort
        
        return when (connectionValidator.validateConnection(host, finalPort)) {
            is RoonConnectionValidator.ConnectionResult.Success -> {
                Log.i(TAG, "Connection to $host:$finalPort validated successfully")
                Pair(host, finalPort)
            }
            else -> {
                Log.w(TAG, "Connection to $host:$finalPort validation failed")
                null
            }
        }
    }

    /**
     * 获取WebSocket连接URL
     */
    fun getWebSocketUrl(host: String, port: Int): String {
        return "ws://$host:$port/api"
    }

    /**
     * 解析用户输入的主机和端口
     */
    private fun parseHostAndPort(input: String): Pair<String, Int> {
        return if (input.contains(":")) {
            val parts = input.split(":")
            val host = parts[0].trim()
            val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            Pair(host, port)
        } else {
            Pair(input.trim(), 0)
        }
    }
}
