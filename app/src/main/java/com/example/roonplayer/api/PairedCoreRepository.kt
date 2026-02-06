package com.example.roonplayer.api

import android.content.SharedPreferences

data class PairedCoreRecord(
    val hostPort: String,
    val host: String,
    val port: Int,
    val token: String,
    val coreId: String,
    val lastConnected: Long
)

enum class TokenMigrationStatus {
    ALREADY_EXISTS,
    MIGRATED,
    NO_LEGACY_TOKEN
}

data class RegistrationTokenSaveResult(
    val coreId: String?
)

class PairedCoreRepository(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        private const val TOKEN_PREFIX = "roon_core_token_"
        private const val TOKEN_BY_CORE_ID_PREFIX = "roon_core_token_by_core_id_"
        private const val CORE_ID_BY_HOST_PREFIX = "roon_core_id_"
        private const val LAST_CONNECTED_BY_HOST_PREFIX = "roon_last_connected_"
        private const val LAST_CONNECTED_BY_CORE_ID_PREFIX = "roon_last_connected_by_core_id_"
    }

    fun loadPairedCores(
        defaultPort: Int,
        isValidHost: (String) -> Boolean,
        fallbackLastSuccessful: LastSuccessfulConnectionState?
    ): Map<String, PairedCoreRecord> {
        val allPrefs = sharedPreferences.all
        val paired = linkedMapOf<String, PairedCoreRecord>()

        fun upsert(
            hostPort: String,
            token: String,
            coreId: String,
            lastConnected: Long
        ) {
            val (host, port) = parseHostPort(hostPort, defaultPort)
            if (!isValidHost(host)) {
                return
            }

            val existing = paired[hostPort]
            if (existing != null && existing.lastConnected > lastConnected) {
                return
            }

            paired[hostPort] = PairedCoreRecord(
                hostPort = hostPort,
                host = host,
                port = port,
                token = token,
                coreId = coreId,
                lastConnected = lastConnected
            )
        }

        for ((key, value) in allPrefs) {
            if (!key.startsWith(TOKEN_PREFIX) || key.startsWith(TOKEN_BY_CORE_ID_PREFIX)) {
                continue
            }

            val token = value as? String ?: continue
            val hostPort = key.removePrefix(TOKEN_PREFIX)
            val coreId = sharedPreferences.getString(coreIdByHostKey(hostPort), "") ?: ""
            val lastConnected = sharedPreferences.getLong(lastConnectedByHostKey(hostPort), 0L)
            upsert(hostPort, token, coreId, lastConnected)
        }

        for ((key, value) in allPrefs) {
            if (!key.startsWith(TOKEN_BY_CORE_ID_PREFIX)) {
                continue
            }

            val token = value as? String ?: continue
            val coreId = key.removePrefix(TOKEN_BY_CORE_ID_PREFIX)
            val hostPort = findLatestHostPortByCoreId(allPrefs, coreId)
                ?: fallbackHostPort(fallbackLastSuccessful)
                ?: continue

            val lastConnected = sharedPreferences.getLong(
                lastConnectedByCoreIdKey(coreId),
                fallbackLastSuccessful?.lastConnectionTime ?: 0L
            )
            upsert(hostPort, token, coreId, lastConnected)
        }

        return paired
    }

    fun migrateLegacyTokenToCoreId(
        coreId: String,
        hostInput: String
    ): TokenMigrationStatus {
        val existingToken = sharedPreferences.getString(tokenByCoreIdKey(coreId), null)
        if (existingToken != null) {
            return TokenMigrationStatus.ALREADY_EXISTS
        }

        val oldToken = sharedPreferences.getString(tokenByHostKey(hostInput), null)
        if (oldToken == null) {
            return TokenMigrationStatus.NO_LEGACY_TOKEN
        }

        val oldLastConnected = sharedPreferences.getLong(lastConnectedByHostKey(hostInput), 0L)

        val editor = sharedPreferences.edit()
        editor.putString(tokenByCoreIdKey(coreId), oldToken)
        if (oldLastConnected > 0L) {
            editor.putLong(lastConnectedByCoreIdKey(coreId), oldLastConnected)
        }
        editor.remove(tokenByHostKey(hostInput))
        editor.remove(lastConnectedByHostKey(hostInput))
        editor.apply()

        return TokenMigrationStatus.MIGRATED
    }

    fun saveCoreId(hostInput: String, coreId: String) {
        sharedPreferences.edit()
            .putString(coreIdByHostKey(hostInput), coreId)
            .apply()
    }

    fun getCoreId(hostInput: String): String? {
        return sharedPreferences.getString(coreIdByHostKey(hostInput), null)
    }

    fun getSavedToken(hostInput: String): String? {
        val coreId = getCoreId(hostInput)
        return if (coreId != null) {
            sharedPreferences.getString(tokenByCoreIdKey(coreId), null)
        } else {
            sharedPreferences.getString(tokenByHostKey(hostInput), null)
        }
    }

    fun saveRegistrationToken(
        hostInput: String,
        token: String,
        connectedAt: Long
    ): RegistrationTokenSaveResult {
        val coreId = getCoreId(hostInput)
        val editor = sharedPreferences.edit()
        if (coreId != null) {
            editor.putString(tokenByCoreIdKey(coreId), token)
            editor.putLong(lastConnectedByCoreIdKey(coreId), connectedAt)

            // 为什么这里主动删除旧键：避免同一 Core 在新旧两套键下并存，
            // 否则后续加载会出现重复候选并污染“最近连接”判断。
            editor.remove(tokenByHostKey(hostInput))
            editor.remove(lastConnectedByHostKey(hostInput))
        } else {
            editor.putString(tokenByHostKey(hostInput), token)
            editor.putLong(lastConnectedByHostKey(hostInput), connectedAt)
        }
        editor.apply()

        return RegistrationTokenSaveResult(coreId = coreId)
    }

    private fun findLatestHostPortByCoreId(
        allPrefs: Map<String, *>,
        coreId: String
    ): String? {
        var matchedHostPort: String? = null
        var latestConnectionTime = Long.MIN_VALUE

        for ((key, value) in allPrefs) {
            if (!key.startsWith(CORE_ID_BY_HOST_PREFIX)) {
                continue
            }
            if (value != coreId) {
                continue
            }

            val hostPort = key.removePrefix(CORE_ID_BY_HOST_PREFIX)
            val lastConnected = sharedPreferences.getLong(lastConnectedByHostKey(hostPort), 0L)
            if (lastConnected >= latestConnectionTime) {
                latestConnectionTime = lastConnected
                matchedHostPort = hostPort
            }
        }

        return matchedHostPort
    }

    private fun fallbackHostPort(
        fallbackLastSuccessful: LastSuccessfulConnectionState?
    ): String? {
        if (fallbackLastSuccessful == null) {
            return null
        }

        val host = fallbackLastSuccessful.host
        val port = fallbackLastSuccessful.port
        if (host.isNullOrBlank() || port <= 0) {
            return null
        }

        return "$host:$port"
    }

    private fun parseHostPort(
        hostPort: String,
        defaultPort: Int
    ): Pair<String, Int> {
        return if (hostPort.contains(":")) {
            val parts = hostPort.split(":")
            parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: defaultPort)
        } else {
            hostPort to defaultPort
        }
    }

    private fun tokenByHostKey(hostInput: String): String {
        return "$TOKEN_PREFIX$hostInput"
    }

    private fun tokenByCoreIdKey(coreId: String): String {
        return "$TOKEN_BY_CORE_ID_PREFIX$coreId"
    }

    private fun coreIdByHostKey(hostInput: String): String {
        return "$CORE_ID_BY_HOST_PREFIX$hostInput"
    }

    private fun lastConnectedByHostKey(hostInput: String): String {
        return "$LAST_CONNECTED_BY_HOST_PREFIX$hostInput"
    }

    private fun lastConnectedByCoreIdKey(coreId: String): String {
        return "$LAST_CONNECTED_BY_CORE_ID_PREFIX$coreId"
    }
}
