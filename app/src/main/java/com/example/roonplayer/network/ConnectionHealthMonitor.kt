package com.example.roonplayer.network

import android.util.Log
import kotlinx.coroutines.*

class ConnectionHealthMonitor(
    private val connectionValidator: RoonConnectionValidator,
    private val defaultCheckIntervalMs: Long,
    private val quickCheckIntervalMs: Long,
    private val healthCheckTimeoutMs: Int
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
            Log.w(TAG, "Health monitor is already running")
            return
        }

        Log.i(TAG, "Starting health monitor: $ip:$port")
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
                    Log.e(TAG, "Error during health check", e)
                    onHealthChange(HealthStatus.Error("Health check error: ${e.message}"))
                }

                delay(currentInterval)
            }
        }
    }

    fun stopMonitoring() {
        Log.i(TAG, "Stopping health monitor")
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringScope.coroutineContext.cancelChildren()
        monitoringJob = null
        consecutiveFailures = 0
        currentInterval = defaultCheckIntervalMs
    }

    private suspend fun checkConnectionHealth(ip: String, port: Int): HealthStatus = withContext(Dispatchers.IO) {
        Log.d(TAG, "Running health check: $ip:$port")

        try {
            when (val result = connectionValidator.validateConnection(ip, port, healthCheckTimeoutMs)) {
                is RoonConnectionValidator.ConnectionResult.Success -> {
                    Log.d(TAG, "Health check succeeded")
                    consecutiveFailures = 0
                    HealthStatus.Healthy
                }
                
                is RoonConnectionValidator.ConnectionResult.Timeout -> {
                    consecutiveFailures++
                    Log.w(TAG, "Health check timeout (consecutive failures: $consecutiveFailures)")
                    
                    when {
                        consecutiveFailures >= 3 -> HealthStatus.Unhealthy
                        consecutiveFailures >= 1 -> HealthStatus.Degraded
                        else -> HealthStatus.Healthy
                    }
                }
                
                is RoonConnectionValidator.ConnectionResult.NetworkError -> {
                    consecutiveFailures++
                    Log.w(TAG, "Health check network error (consecutive failures: $consecutiveFailures)")
                    
                    when {
                        consecutiveFailures >= 3 -> HealthStatus.Unhealthy
                        else -> HealthStatus.Degraded
                    }
                }
                
                is RoonConnectionValidator.ConnectionResult.InvalidCore -> {
                    Log.e(TAG, "Health check found invalid Core: ${result.message}")
                    HealthStatus.Error(result.message)
                }
            }
            
        } catch (e: Exception) {
            consecutiveFailures++
            Log.e(TAG, "Health check exception", e)
            HealthStatus.Error("Health check exception: ${e.message}")
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
            Log.d(TAG, "Adjusting monitor interval: ${currentInterval}ms -> ${newInterval}ms")
            currentInterval = newInterval
        }
    }

}
