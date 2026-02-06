package com.example.roonplayer.network

import android.util.Log
import kotlinx.coroutines.*

class ConnectionHealthMonitor(
    private val connectionValidator: RoonConnectionValidator,
    private val defaultCheckIntervalMs: Long,
    private val quickCheckIntervalMs: Long
) {
    companion object {
        private const val TAG = "ConnectionHealthMonitor"
    }

    sealed class HealthStatus {
        object Healthy : HealthStatus()
        object Degraded : HealthStatus()
        object Unhealthy : HealthStatus()
        data class Error(val message: String) : HealthStatus()
    }

    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var currentInterval = defaultCheckIntervalMs

    private var consecutiveFailures = 0
    private var isMonitoring = false

    fun startMonitoring(
        ip: String,
        port: Int,
        onHealthChange: (HealthStatus) -> Unit
    ) {
        if (isMonitoring) {
            Log.w(TAG, "健康监控已在运行")
            return
        }

        Log.i(TAG, "开始健康监控: $ip:$port")
        isMonitoring = true
        consecutiveFailures = 0
        currentInterval = defaultCheckIntervalMs

        monitoringJob = monitoringScope.launch {
            while (isActive && isMonitoring) {
                try {
                    val healthStatus = checkConnectionHealth(ip, port)
                    onHealthChange(healthStatus)
                    
                    adjustMonitoringInterval(healthStatus)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "健康检查过程中发生错误", e)
                    onHealthChange(HealthStatus.Error("健康检查错误: ${e.message}"))
                }

                delay(currentInterval)
            }
        }
    }

    fun stopMonitoring() {
        Log.i(TAG, "停止健康监控")
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringScope.coroutineContext.cancelChildren()
        monitoringJob = null
        consecutiveFailures = 0
        currentInterval = defaultCheckIntervalMs
    }

    private suspend fun checkConnectionHealth(ip: String, port: Int): HealthStatus = withContext(Dispatchers.IO) {
        Log.d(TAG, "执行健康检查: $ip:$port")

        try {
            when (val result = connectionValidator.validateConnection(ip, port)) {
                is RoonConnectionValidator.ConnectionResult.Success -> {
                    Log.d(TAG, "健康检查成功")
                    consecutiveFailures = 0
                    HealthStatus.Healthy
                }
                
                is RoonConnectionValidator.ConnectionResult.Timeout -> {
                    consecutiveFailures++
                    Log.w(TAG, "健康检查超时 (连续失败: $consecutiveFailures)")
                    
                    when {
                        consecutiveFailures >= 3 -> HealthStatus.Unhealthy
                        consecutiveFailures >= 1 -> HealthStatus.Degraded
                        else -> HealthStatus.Healthy
                    }
                }
                
                is RoonConnectionValidator.ConnectionResult.NetworkError -> {
                    consecutiveFailures++
                    Log.w(TAG, "健康检查网络错误 (连续失败: $consecutiveFailures)")
                    
                    when {
                        consecutiveFailures >= 2 -> HealthStatus.Unhealthy
                        else -> HealthStatus.Degraded
                    }
                }
                
                is RoonConnectionValidator.ConnectionResult.InvalidCore -> {
                    Log.e(TAG, "健康检查发现无效Core: ${result.message}")
                    HealthStatus.Error(result.message)
                }
            }
            
        } catch (e: Exception) {
            consecutiveFailures++
            Log.e(TAG, "健康检查异常", e)
            HealthStatus.Error("健康检查异常: ${e.message}")
        }
    }

    private fun adjustMonitoringInterval(healthStatus: HealthStatus) {
        val newInterval = when (healthStatus) {
            is HealthStatus.Healthy -> {
                if (consecutiveFailures == 0) defaultCheckIntervalMs else quickCheckIntervalMs
            }
            is HealthStatus.Degraded -> quickCheckIntervalMs
            is HealthStatus.Unhealthy -> quickCheckIntervalMs
            is HealthStatus.Error -> defaultCheckIntervalMs
        }

        if (newInterval != currentInterval) {
            Log.d(TAG, "调整监控间隔: ${currentInterval}ms -> ${newInterval}ms")
            currentInterval = newInterval
        }
    }

}
