package com.example.roonplayer.config

import com.example.roonplayer.domain.DiscoveryTimingConfig

data class RuntimeConfigOverrideRecord(
    val key: String,
    val rawValue: String,
    val appliedValue: String,
    val source: String
)

data class RuntimeConfigResolution(
    val config: AppRuntimeConfig,
    val sourceName: String,
    val overrides: List<RuntimeConfigOverrideRecord>,
    val warnings: List<String>
) {
    fun snapshotLines(): List<String> {
        return listOf(
            "connection.web_socket_port=${config.connection.webSocketPort}",
            "connection.tcp_connect_timeout_ms=${config.connection.tcpConnectTimeoutMs}",
            "connection.ws_connect_timeout_ms=${config.connection.webSocketConnectTimeoutMs}",
            "connection.smart_retry=max_attempts=${config.connection.smartRetryMaxAttempts},initial_ms=${config.connection.smartRetryInitialDelayMs},max_ms=${config.connection.smartRetryMaxDelayMs}",
            "discovery.network=group=${config.discoveryNetwork.multicastGroup},port=${config.discoveryNetwork.discoveryPort},broadcast=${config.discoveryNetwork.broadcastAddress}",
            "discovery.timing=scan_interval_ms=${config.discoveryTiming.networkScanIntervalMs},announcement_window_ms=${config.discoveryTiming.announcementListenWindowMs},active_sood_window_ms=${config.discoveryTiming.activeSoodListenWindowMs}",
            "ui.timing=multi_click_ms=${config.uiTiming.multiClickTimeDeltaMs},single_click_ms=${config.uiTiming.singleClickDelayMs},startup_settle_ms=${config.uiTiming.startupUiSettleDelayMs}",
            "cache=max_images=${config.cache.maxCachedImages},display=${config.cache.maxDisplayCache},preload=${config.cache.maxPreloadCache},memory_threshold_bytes=${config.cache.memoryThresholdBytes}"
        )
    }
}

object RuntimeConfigKeys {
    const val CONNECTION_WEB_SOCKET_PORT = "connection.web_socket_port"
    const val CONNECTION_TCP_CONNECT_TIMEOUT_MS = "connection.tcp_connect_timeout_ms"
    const val CONNECTION_WS_CONNECT_TIMEOUT_MS = "connection.ws_connect_timeout_ms"
    const val CONNECTION_WS_HANDSHAKE_TIMEOUT_MS = "connection.ws_handshake_timeout_ms"
    const val CONNECTION_WS_READ_TIMEOUT_MS = "connection.ws_read_timeout_ms"
    const val CONNECTION_SMART_RETRY_MAX_ATTEMPTS = "connection.smart_retry_max_attempts"
    const val CONNECTION_SMART_RETRY_INITIAL_DELAY_MS = "connection.smart_retry_initial_delay_ms"
    const val CONNECTION_SMART_RETRY_MAX_DELAY_MS = "connection.smart_retry_max_delay_ms"
    const val CONNECTION_HEALTH_CHECK_INTERVAL_MS = "connection.health_check_interval_ms"
    const val CONNECTION_HEALTH_QUICK_CHECK_INTERVAL_MS = "connection.health_quick_check_interval_ms"
    const val CONNECTION_NETWORK_READY_TIMEOUT_MS = "connection.network_ready_timeout_ms"
    const val CONNECTION_NETWORK_READY_POLL_INTERVAL_MS = "connection.network_ready_poll_interval_ms"
    const val CONNECTION_NETWORK_CHECK_TIMEOUT_MS = "connection.network_check_timeout_ms"
    const val CONNECTION_NETWORK_TEST_HOST = "connection.network_test_host"
    const val CONNECTION_NETWORK_TEST_PORT = "connection.network_test_port"
    const val CONNECTION_AUTO_CONNECT_DELAY_MS = "connection.auto_connect_delay_ms"
    const val CONNECTION_AUTO_DISCOVERY_DELAY_MS = "connection.auto_discovery_delay_ms"
    const val CONNECTION_HISTORY_RETENTION_MS = "connection.history_retention_ms"
    const val CONNECTION_LONG_PAUSE_RECONNECT_THRESHOLD_MS = "connection.long_pause_reconnect_threshold_ms"
    const val CONNECTION_LONG_STOP_RECONNECT_THRESHOLD_MS = "connection.long_stop_reconnect_threshold_ms"
    const val CONNECTION_SMART_RECONNECT_MAX_BACKOFF_MS = "connection.smart_reconnect_max_backoff_ms"

    const val DISCOVERY_NETWORK_PORT = "discovery.network.port"
    const val DISCOVERY_NETWORK_MULTICAST_GROUP = "discovery.network.multicast_group"
    const val DISCOVERY_NETWORK_SERVICE_ID = "discovery.network.service_id"
    const val DISCOVERY_NETWORK_BROADCAST_ADDRESS = "discovery.network.broadcast_address"

    const val DISCOVERY_TIMING_SCAN_INTERVAL_MS = "discovery.timing.scan_interval_ms"
    const val DISCOVERY_TIMING_DIRECT_DETECTION_WAIT_MS = "discovery.timing.direct_detection_wait_ms"
    const val DISCOVERY_TIMING_ANNOUNCEMENT_SOCKET_TIMEOUT_MS = "discovery.timing.announcement_socket_timeout_ms"
    const val DISCOVERY_TIMING_ANNOUNCEMENT_LISTEN_WINDOW_MS = "discovery.timing.announcement_listen_window_ms"
    const val DISCOVERY_TIMING_ACTIVE_SOOD_SOCKET_TIMEOUT_MS = "discovery.timing.active_sood_socket_timeout_ms"
    const val DISCOVERY_TIMING_ACTIVE_SOOD_LISTEN_WINDOW_MS = "discovery.timing.active_sood_listen_window_ms"

    const val UI_TIMING_MULTI_CLICK_DELTA_MS = "ui.timing.multi_click_delta_ms"
    const val UI_TIMING_SINGLE_CLICK_DELAY_MS = "ui.timing.single_click_delay_ms"
    const val UI_TIMING_ART_WALL_UPDATE_INTERVAL_MS = "ui.timing.art_wall_update_interval_ms"
    const val UI_TIMING_ART_WALL_STATS_LOG_DELAY_MS = "ui.timing.art_wall_stats_log_delay_ms"
    const val UI_TIMING_DELAYED_ART_WALL_SWITCH_DELAY_MS = "ui.timing.delayed_art_wall_switch_delay_ms"
    const val UI_TIMING_RESET_DISPLAY_ART_WALL_DELAY_MS = "ui.timing.reset_display_art_wall_delay_ms"
    const val UI_TIMING_STARTUP_UI_SETTLE_DELAY_MS = "ui.timing.startup_ui_settle_delay_ms"

    const val CACHE_MAX_CACHED_IMAGES = "cache.max_cached_images"
    const val CACHE_MAX_DISPLAY_CACHE = "cache.max_display_cache"
    const val CACHE_MAX_PRELOAD_CACHE = "cache.max_preload_cache"
    const val CACHE_MEMORY_THRESHOLD_BYTES = "cache.memory_threshold_bytes"
}

class RuntimeConfigResolver(
    private val defaults: AppRuntimeConfig = AppRuntimeConfig.defaults()
) {
    fun resolve(
        overrides: Map<String, String>,
        sourceName: String
    ): RuntimeConfigResolution {
        val records = mutableListOf<RuntimeConfigOverrideRecord>()
        val warnings = mutableListOf<String>()

        fun applyInt(
            key: String,
            defaultValue: Int,
            min: Int,
            max: Int
        ): Int {
            val raw = overrides[key] ?: return defaultValue
            val parsed = raw.toIntOrNull()
            if (parsed == null) {
                warnings += "Invalid int override: $key=$raw, fallback=$defaultValue"
                return defaultValue
            }
            val bounded = parsed.coerceIn(min, max)
            if (bounded != parsed) {
                warnings += "Out-of-range override clamped: $key=$parsed -> $bounded (range=$min..$max)"
            }
            records += RuntimeConfigOverrideRecord(
                key = key,
                rawValue = raw,
                appliedValue = bounded.toString(),
                source = sourceName
            )
            return bounded
        }

        fun applyLong(
            key: String,
            defaultValue: Long,
            min: Long,
            max: Long
        ): Long {
            val raw = overrides[key] ?: return defaultValue
            val parsed = raw.toLongOrNull()
            if (parsed == null) {
                warnings += "Invalid long override: $key=$raw, fallback=$defaultValue"
                return defaultValue
            }
            val bounded = parsed.coerceIn(min, max)
            if (bounded != parsed) {
                warnings += "Out-of-range override clamped: $key=$parsed -> $bounded (range=$min..$max)"
            }
            records += RuntimeConfigOverrideRecord(
                key = key,
                rawValue = raw,
                appliedValue = bounded.toString(),
                source = sourceName
            )
            return bounded
        }

        fun applyString(
            key: String,
            defaultValue: String,
            validator: (String) -> Boolean
        ): String {
            val raw = overrides[key] ?: return defaultValue
            val normalized = raw.trim()
            if (!validator(normalized)) {
                warnings += "Invalid string override: $key=$raw, fallback=$defaultValue"
                return defaultValue
            }
            records += RuntimeConfigOverrideRecord(
                key = key,
                rawValue = raw,
                appliedValue = normalized,
                source = sourceName
            )
            return normalized
        }

        val connection = defaults.connection.copy(
            webSocketPort = applyInt(
                RuntimeConfigKeys.CONNECTION_WEB_SOCKET_PORT,
                defaults.connection.webSocketPort,
                RuntimeConfigBounds.PORT_MIN,
                RuntimeConfigBounds.PORT_MAX
            ),
            tcpConnectTimeoutMs = applyInt(
                RuntimeConfigKeys.CONNECTION_TCP_CONNECT_TIMEOUT_MS,
                defaults.connection.tcpConnectTimeoutMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS,
                RuntimeConfigBounds.TIMEOUT_MAX_MS
            ),
            webSocketConnectTimeoutMs = applyInt(
                RuntimeConfigKeys.CONNECTION_WS_CONNECT_TIMEOUT_MS,
                defaults.connection.webSocketConnectTimeoutMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS,
                RuntimeConfigBounds.TIMEOUT_MAX_MS
            ),
            webSocketHandshakeTimeoutMs = applyInt(
                RuntimeConfigKeys.CONNECTION_WS_HANDSHAKE_TIMEOUT_MS,
                defaults.connection.webSocketHandshakeTimeoutMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS,
                RuntimeConfigBounds.TIMEOUT_MAX_MS
            ),
            webSocketReadTimeoutMs = applyInt(
                RuntimeConfigKeys.CONNECTION_WS_READ_TIMEOUT_MS,
                defaults.connection.webSocketReadTimeoutMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS,
                RuntimeConfigBounds.READ_TIMEOUT_MAX_MS
            ),
            smartRetryMaxAttempts = applyInt(
                RuntimeConfigKeys.CONNECTION_SMART_RETRY_MAX_ATTEMPTS,
                defaults.connection.smartRetryMaxAttempts,
                RuntimeConfigBounds.RETRY_ATTEMPTS_MIN,
                RuntimeConfigBounds.RETRY_ATTEMPTS_MAX
            ),
            smartRetryInitialDelayMs = applyLong(
                RuntimeConfigKeys.CONNECTION_SMART_RETRY_INITIAL_DELAY_MS,
                defaults.connection.smartRetryInitialDelayMs,
                RuntimeConfigBounds.DELAY_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            ),
            smartRetryMaxDelayMs = applyLong(
                RuntimeConfigKeys.CONNECTION_SMART_RETRY_MAX_DELAY_MS,
                defaults.connection.smartRetryMaxDelayMs,
                RuntimeConfigBounds.DELAY_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            ),
            healthCheckIntervalMs = applyLong(
                RuntimeConfigKeys.CONNECTION_HEALTH_CHECK_INTERVAL_MS,
                defaults.connection.healthCheckIntervalMs,
                RuntimeConfigBounds.HEALTH_INTERVAL_MIN_MS,
                RuntimeConfigBounds.HEALTH_INTERVAL_MAX_MS
            ),
            healthQuickCheckIntervalMs = applyLong(
                RuntimeConfigKeys.CONNECTION_HEALTH_QUICK_CHECK_INTERVAL_MS,
                defaults.connection.healthQuickCheckIntervalMs,
                RuntimeConfigBounds.HEALTH_INTERVAL_MIN_MS,
                RuntimeConfigBounds.HEALTH_INTERVAL_MAX_MS
            ),
            networkReadyTimeoutMs = applyLong(
                RuntimeConfigKeys.CONNECTION_NETWORK_READY_TIMEOUT_MS,
                defaults.connection.networkReadyTimeoutMs,
                RuntimeConfigBounds.NETWORK_READY_TIMEOUT_MIN_MS,
                RuntimeConfigBounds.NETWORK_READY_TIMEOUT_MAX_MS
            ),
            networkReadyPollIntervalMs = applyLong(
                RuntimeConfigKeys.CONNECTION_NETWORK_READY_POLL_INTERVAL_MS,
                defaults.connection.networkReadyPollIntervalMs,
                RuntimeConfigBounds.NETWORK_READY_POLL_INTERVAL_MIN_MS,
                RuntimeConfigBounds.NETWORK_READY_POLL_INTERVAL_MAX_MS
            ),
            networkConnectivityCheckTimeoutMs = applyInt(
                RuntimeConfigKeys.CONNECTION_NETWORK_CHECK_TIMEOUT_MS,
                defaults.connection.networkConnectivityCheckTimeoutMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS,
                RuntimeConfigBounds.TIMEOUT_MAX_MS
            ),
            networkTestHost = applyString(
                RuntimeConfigKeys.CONNECTION_NETWORK_TEST_HOST,
                defaults.connection.networkTestHost
            ) { value -> value.isNotBlank() && value.length <= RuntimeConfigBounds.HOST_MAX_LENGTH },
            networkTestPort = applyInt(
                RuntimeConfigKeys.CONNECTION_NETWORK_TEST_PORT,
                defaults.connection.networkTestPort,
                RuntimeConfigBounds.PORT_MIN,
                RuntimeConfigBounds.PORT_MAX
            ),
            autoConnectDelayMs = applyLong(
                RuntimeConfigKeys.CONNECTION_AUTO_CONNECT_DELAY_MS,
                defaults.connection.autoConnectDelayMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            ),
            autoDiscoveryDelayMs = applyLong(
                RuntimeConfigKeys.CONNECTION_AUTO_DISCOVERY_DELAY_MS,
                defaults.connection.autoDiscoveryDelayMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            ),
            connectionHistoryRetentionMs = applyLong(
                RuntimeConfigKeys.CONNECTION_HISTORY_RETENTION_MS,
                defaults.connection.connectionHistoryRetentionMs,
                RuntimeConfigBounds.RETENTION_MIN_MS,
                RuntimeConfigBounds.RETENTION_MAX_MS
            ),
            longPauseReconnectThresholdMs = applyLong(
                RuntimeConfigKeys.CONNECTION_LONG_PAUSE_RECONNECT_THRESHOLD_MS,
                defaults.connection.longPauseReconnectThresholdMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.RECONNECT_THRESHOLD_MAX_MS
            ),
            longStopReconnectThresholdMs = applyLong(
                RuntimeConfigKeys.CONNECTION_LONG_STOP_RECONNECT_THRESHOLD_MS,
                defaults.connection.longStopReconnectThresholdMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.RECONNECT_THRESHOLD_MAX_MS
            ),
            smartReconnectMaxBackoffMs = applyLong(
                RuntimeConfigKeys.CONNECTION_SMART_RECONNECT_MAX_BACKOFF_MS,
                defaults.connection.smartReconnectMaxBackoffMs,
                RuntimeConfigBounds.DELAY_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            )
        )

        val discoveryNetwork = defaults.discoveryNetwork.copy(
            discoveryPort = applyInt(
                RuntimeConfigKeys.DISCOVERY_NETWORK_PORT,
                defaults.discoveryNetwork.discoveryPort,
                RuntimeConfigBounds.PORT_MIN,
                RuntimeConfigBounds.PORT_MAX
            ),
            multicastGroup = applyString(
                RuntimeConfigKeys.DISCOVERY_NETWORK_MULTICAST_GROUP,
                defaults.discoveryNetwork.multicastGroup
            ) { isValidIpv4Literal(it) },
            soodServiceId = applyString(
                RuntimeConfigKeys.DISCOVERY_NETWORK_SERVICE_ID,
                defaults.discoveryNetwork.soodServiceId
            ) { value -> value.isNotBlank() && value.length <= RuntimeConfigBounds.SERVICE_ID_MAX_LENGTH },
            broadcastAddress = applyString(
                RuntimeConfigKeys.DISCOVERY_NETWORK_BROADCAST_ADDRESS,
                defaults.discoveryNetwork.broadcastAddress
            ) { isValidIpv4Literal(it) }
        )

        val discoveryTiming = DiscoveryTimingConfig(
            networkScanIntervalMs = applyLong(
                RuntimeConfigKeys.DISCOVERY_TIMING_SCAN_INTERVAL_MS,
                defaults.discoveryTiming.networkScanIntervalMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.SCAN_INTERVAL_MAX_MS
            ),
            directDetectionWaitMs = applyLong(
                RuntimeConfigKeys.DISCOVERY_TIMING_DIRECT_DETECTION_WAIT_MS,
                defaults.discoveryTiming.directDetectionWaitMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.DETECTION_WAIT_MAX_MS
            ),
            announcementSocketTimeoutMs = applyInt(
                RuntimeConfigKeys.DISCOVERY_TIMING_ANNOUNCEMENT_SOCKET_TIMEOUT_MS,
                defaults.discoveryTiming.announcementSocketTimeoutMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS,
                RuntimeConfigBounds.TIMEOUT_MAX_MS
            ),
            announcementListenWindowMs = applyLong(
                RuntimeConfigKeys.DISCOVERY_TIMING_ANNOUNCEMENT_LISTEN_WINDOW_MS,
                defaults.discoveryTiming.announcementListenWindowMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS.toLong(),
                RuntimeConfigBounds.DISCOVERY_LISTEN_WINDOW_MAX_MS
            ),
            activeSoodSocketTimeoutMs = applyInt(
                RuntimeConfigKeys.DISCOVERY_TIMING_ACTIVE_SOOD_SOCKET_TIMEOUT_MS,
                defaults.discoveryTiming.activeSoodSocketTimeoutMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS,
                RuntimeConfigBounds.TIMEOUT_MAX_MS
            ),
            activeSoodListenWindowMs = applyLong(
                RuntimeConfigKeys.DISCOVERY_TIMING_ACTIVE_SOOD_LISTEN_WINDOW_MS,
                defaults.discoveryTiming.activeSoodListenWindowMs,
                RuntimeConfigBounds.TIMEOUT_MIN_MS.toLong(),
                RuntimeConfigBounds.DISCOVERY_LISTEN_WINDOW_MAX_MS
            )
        )

        val uiTiming = defaults.uiTiming.copy(
            multiClickTimeDeltaMs = applyLong(
                RuntimeConfigKeys.UI_TIMING_MULTI_CLICK_DELTA_MS,
                defaults.uiTiming.multiClickTimeDeltaMs,
                RuntimeConfigBounds.UI_CLICK_WINDOW_MIN_MS,
                RuntimeConfigBounds.UI_CLICK_WINDOW_MAX_MS
            ),
            singleClickDelayMs = applyLong(
                RuntimeConfigKeys.UI_TIMING_SINGLE_CLICK_DELAY_MS,
                defaults.uiTiming.singleClickDelayMs,
                RuntimeConfigBounds.UI_CLICK_WINDOW_MIN_MS,
                RuntimeConfigBounds.UI_CLICK_WINDOW_MAX_MS
            ),
            artWallUpdateIntervalMs = applyLong(
                RuntimeConfigKeys.UI_TIMING_ART_WALL_UPDATE_INTERVAL_MS,
                defaults.uiTiming.artWallUpdateIntervalMs,
                RuntimeConfigBounds.UI_ANIMATION_INTERVAL_MIN_MS,
                RuntimeConfigBounds.UI_ANIMATION_INTERVAL_MAX_MS
            ),
            artWallStatsLogDelayMs = applyLong(
                RuntimeConfigKeys.UI_TIMING_ART_WALL_STATS_LOG_DELAY_MS,
                defaults.uiTiming.artWallStatsLogDelayMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            ),
            delayedArtWallSwitchDelayMs = applyLong(
                RuntimeConfigKeys.UI_TIMING_DELAYED_ART_WALL_SWITCH_DELAY_MS,
                defaults.uiTiming.delayedArtWallSwitchDelayMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            ),
            resetDisplayArtWallDelayMs = applyLong(
                RuntimeConfigKeys.UI_TIMING_RESET_DISPLAY_ART_WALL_DELAY_MS,
                defaults.uiTiming.resetDisplayArtWallDelayMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            ),
            startupUiSettleDelayMs = applyLong(
                RuntimeConfigKeys.UI_TIMING_STARTUP_UI_SETTLE_DELAY_MS,
                defaults.uiTiming.startupUiSettleDelayMs,
                RuntimeConfigBounds.NON_NEGATIVE_MIN_MS,
                RuntimeConfigBounds.DELAY_MAX_MS
            )
        )

        val cache = defaults.cache.copy(
            maxCachedImages = applyInt(
                RuntimeConfigKeys.CACHE_MAX_CACHED_IMAGES,
                defaults.cache.maxCachedImages,
                RuntimeConfigBounds.CACHE_IMAGES_MIN,
                RuntimeConfigBounds.CACHE_IMAGES_MAX
            ),
            maxDisplayCache = applyInt(
                RuntimeConfigKeys.CACHE_MAX_DISPLAY_CACHE,
                defaults.cache.maxDisplayCache,
                RuntimeConfigBounds.CACHE_BUCKET_MIN,
                RuntimeConfigBounds.CACHE_BUCKET_MAX
            ),
            maxPreloadCache = applyInt(
                RuntimeConfigKeys.CACHE_MAX_PRELOAD_CACHE,
                defaults.cache.maxPreloadCache,
                RuntimeConfigBounds.CACHE_BUCKET_MIN,
                RuntimeConfigBounds.CACHE_BUCKET_MAX
            ),
            memoryThresholdBytes = applyLong(
                RuntimeConfigKeys.CACHE_MEMORY_THRESHOLD_BYTES,
                defaults.cache.memoryThresholdBytes,
                RuntimeConfigBounds.MEMORY_THRESHOLD_MIN_BYTES,
                RuntimeConfigBounds.MEMORY_THRESHOLD_MAX_BYTES
            )
        )

        // 为什么在最终结果里返回覆盖记录和告警：
        // “值是多少”不足以排障，必须同时知道值从哪里来、有没有被校验修正。
        return RuntimeConfigResolution(
            config = defaults.copy(
                connection = connection,
                discoveryNetwork = discoveryNetwork,
                discoveryTiming = discoveryTiming,
                uiTiming = uiTiming,
                cache = cache
            ),
            sourceName = sourceName,
            overrides = records,
            warnings = warnings
        )
    }

    private fun isValidIpv4Literal(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != RuntimeConfigBounds.IPV4_SEGMENT_COUNT) {
            return false
        }
        return parts.all { part ->
            val numeric = part.toIntOrNull() ?: return@all false
            numeric in RuntimeConfigBounds.IPV4_OCTET_MIN..RuntimeConfigBounds.IPV4_OCTET_MAX
        }
    }
}

private object RuntimeConfigBounds {
    const val PORT_MIN = 1
    const val PORT_MAX = 65535

    const val TIMEOUT_MIN_MS = 100
    const val TIMEOUT_MAX_MS = 120000
    const val READ_TIMEOUT_MAX_MS = 300000

    const val RETRY_ATTEMPTS_MIN = 1
    const val RETRY_ATTEMPTS_MAX = 20

    const val NON_NEGATIVE_MIN_MS = 0L
    const val DELAY_MIN_MS = 100L
    const val DELAY_MAX_MS = 300000L
    const val RECONNECT_THRESHOLD_MAX_MS = 1800000L

    const val HEALTH_INTERVAL_MIN_MS = 1000L
    const val HEALTH_INTERVAL_MAX_MS = 600000L
    const val NETWORK_READY_TIMEOUT_MIN_MS = 1000L
    const val NETWORK_READY_TIMEOUT_MAX_MS = 600000L
    const val NETWORK_READY_POLL_INTERVAL_MIN_MS = 100L
    const val NETWORK_READY_POLL_INTERVAL_MAX_MS = 60000L

    const val RETENTION_MIN_MS = 3600000L
    const val RETENTION_MAX_MS = 31536000000L

    const val SCAN_INTERVAL_MAX_MS = 10000L
    const val DETECTION_WAIT_MAX_MS = 180000L
    const val DISCOVERY_LISTEN_WINDOW_MAX_MS = 300000L

    const val UI_CLICK_WINDOW_MIN_MS = 50L
    const val UI_CLICK_WINDOW_MAX_MS = 5000L
    const val UI_ANIMATION_INTERVAL_MIN_MS = 1000L
    const val UI_ANIMATION_INTERVAL_MAX_MS = 3600000L

    const val CACHE_IMAGES_MIN = 10
    const val CACHE_IMAGES_MAX = 10000
    const val CACHE_BUCKET_MIN = 1
    const val CACHE_BUCKET_MAX = 500
    const val MEMORY_THRESHOLD_MIN_BYTES = 1048576L
    const val MEMORY_THRESHOLD_MAX_BYTES = 1073741824L

    const val HOST_MAX_LENGTH = 255
    const val SERVICE_ID_MAX_LENGTH = 64

    const val IPV4_SEGMENT_COUNT = 4
    const val IPV4_OCTET_MIN = 0
    const val IPV4_OCTET_MAX = 255
}
