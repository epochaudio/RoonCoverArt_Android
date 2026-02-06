package com.example.roonplayer.api

import android.content.SharedPreferences

data class ConnectionHistoryRecord(
    val ip: String,
    val port: Int,
    val lastSeen: Long,
    val successCount: Int
)

data class LastSuccessfulConnectionState(
    val host: String?,
    val port: Int,
    val lastConnectionTime: Long
)

data class SaveConnectionResult(
    val savedAt: Long,
    val successCount: Int
)

class ConnectionHistoryRepository(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        private const val CONNECTION_PREFIX = "roon_successful_"
        private const val CONNECTION_TIME_SUFFIX = "_time"
        private const val CONNECTION_COUNT_SUFFIX = "_count"
        private const val PORT_SEPARATOR = "_port_"

        private const val LAST_SUCCESSFUL_HOST_KEY = "last_successful_host"
        private const val LAST_SUCCESSFUL_PORT_KEY = "last_successful_port"
        private const val LAST_CONNECTION_TIME_KEY = "last_connection_time"
    }

    fun saveSuccessfulConnection(
        ip: String,
        port: Int,
        isValidHost: (String) -> Boolean,
        now: Long = System.currentTimeMillis()
    ): SaveConnectionResult? {
        // 为什么先做 host 校验：这里是连接决策的持久化入口，脏数据一旦落盘，
        // 会影响后续自动重连和优先级排序，应该在仓储边界统一拦截。
        if (!isValidHost(ip)) {
            return null
        }

        val countKey = connectionCountKey(ip, port)
        val successCount = sharedPreferences.getInt(countKey, 0) + 1

        sharedPreferences.edit()
            .putLong(connectionTimeKey(ip, port), now)
            .putInt(countKey, successCount)
            .putString(LAST_SUCCESSFUL_HOST_KEY, ip)
            .putInt(LAST_SUCCESSFUL_PORT_KEY, port)
            .putLong(LAST_CONNECTION_TIME_KEY, now)
            .apply()

        return SaveConnectionResult(
            savedAt = now,
            successCount = successCount
        )
    }

    fun getSavedSuccessfulConnections(
        isValidHost: (String) -> Boolean
    ): List<Pair<String, Int>> {
        return parseConnectionRecords(isValidHost)
            .sortedByDescending { it.lastSeen }
            .map { record -> record.ip to record.port }
    }

    fun getPrioritizedConnections(
        isValidHost: (String) -> Boolean
    ): List<ConnectionHistoryRecord> {
        return parseConnectionRecords(isValidHost)
            // 为什么先按成功次数，再按最近时间：
            // 优先保证“稳定可连”，再在稳定中选“最新可用”。
            .sortedWith(
                compareByDescending<ConnectionHistoryRecord> { it.successCount }
                    .thenByDescending { it.lastSeen }
            )
    }

    fun cleanupOldConnections(
        retentionMs: Long,
        now: Long = System.currentTimeMillis()
    ): Int {
        val cutoffTime = now - retentionMs
        val editor = sharedPreferences.edit()
        var removedConnections = 0

        for ((key, value) in sharedPreferences.all) {
            if (!key.startsWith(CONNECTION_PREFIX) || !key.endsWith(CONNECTION_TIME_SUFFIX)) {
                continue
            }

            val timeValue = value as? Long ?: continue
            if (timeValue >= cutoffTime) {
                continue
            }

            removedConnections++
            editor.remove(key)
            editor.remove(key.replace(CONNECTION_TIME_SUFFIX, CONNECTION_COUNT_SUFFIX))
        }

        if (removedConnections > 0) {
            editor.apply()
        }

        return removedConnections
    }

    fun getLastSuccessfulConnectionState(): LastSuccessfulConnectionState {
        return LastSuccessfulConnectionState(
            host = sharedPreferences.getString(LAST_SUCCESSFUL_HOST_KEY, null),
            port = sharedPreferences.getInt(LAST_SUCCESSFUL_PORT_KEY, 0),
            lastConnectionTime = sharedPreferences.getLong(LAST_CONNECTION_TIME_KEY, 0L)
        )
    }

    private fun parseConnectionRecords(
        isValidHost: (String) -> Boolean
    ): List<ConnectionHistoryRecord> {
        val records = mutableListOf<ConnectionHistoryRecord>()

        for ((key, value) in sharedPreferences.all) {
            if (!key.startsWith(CONNECTION_PREFIX) || !key.endsWith(CONNECTION_TIME_SUFFIX)) {
                continue
            }

            val lastSeen = value as? Long ?: continue
            val keyWithoutSuffix = key.removePrefix(CONNECTION_PREFIX).removeSuffix(CONNECTION_TIME_SUFFIX)
            val parts = keyWithoutSuffix.split(PORT_SEPARATOR)
            if (parts.size != 2) {
                continue
            }

            val ip = parts[0]
            if (!isValidHost(ip)) {
                continue
            }

            val port = parts[1].toIntOrNull() ?: continue
            val successCount = sharedPreferences.getInt(connectionCountKey(ip, port), 1)
            records.add(
                ConnectionHistoryRecord(
                    ip = ip,
                    port = port,
                    lastSeen = lastSeen,
                    successCount = successCount
                )
            )
        }

        return records
    }

    private fun connectionTimeKey(ip: String, port: Int): String {
        return "${CONNECTION_PREFIX}${ip}${PORT_SEPARATOR}${port}${CONNECTION_TIME_SUFFIX}"
    }

    private fun connectionCountKey(ip: String, port: Int): String {
        return "${CONNECTION_PREFIX}${ip}${PORT_SEPARATOR}${port}${CONNECTION_COUNT_SUFFIX}"
    }
}
