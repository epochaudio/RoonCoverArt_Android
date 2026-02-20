package com.example.roonplayer.config

import com.example.roonplayer.domain.DiscoveryPolicyConfig
import com.example.roonplayer.domain.DiscoveryTimingConfig

data class ConnectionConfig(
    val webSocketPort: Int,
    val tcpConnectTimeoutMs: Int,
    val webSocketConnectTimeoutMs: Int,
    val webSocketHandshakeTimeoutMs: Int,
    val webSocketReadTimeoutMs: Int,
    val smartRetryMaxAttempts: Int,
    val smartRetryInitialDelayMs: Long,
    val smartRetryMaxDelayMs: Long,
    val healthCheckIntervalMs: Long,
    val healthQuickCheckIntervalMs: Long,
    val networkReadyTimeoutMs: Long,
    val networkReadyPollIntervalMs: Long,
    val networkConnectivityCheckTimeoutMs: Int,
    val networkTestHost: String,
    val networkTestPort: Int,
    val autoConnectDelayMs: Long,
    val autoDiscoveryDelayMs: Long,
    val connectionHistoryRetentionMs: Long,
    val longPauseReconnectThresholdMs: Long,
    val longStopReconnectThresholdMs: Long,
    val smartReconnectMaxBackoffMs: Long
)

data class DiscoveryNetworkConfig(
    val discoveryPort: Int,
    val multicastGroup: String,
    val soodServiceId: String,
    val broadcastAddress: String
)

data class UiTimingConfig(
    val multiClickTimeDeltaMs: Long,
    val singleClickDelayMs: Long,
    val artWallUpdateIntervalMs: Long,
    val artWallStatsLogDelayMs: Long,
    val delayedArtWallSwitchDelayMs: Long,
    val resetDisplayArtWallDelayMs: Long,
    val startupUiSettleDelayMs: Long
)

data class CacheConfig(
    val maxCachedImages: Int,
    val maxDisplayCache: Int,
    val maxPreloadCache: Int,
    val memoryThresholdBytes: Long
)

data class FeatureFlagConfig(
    val newSoodCodec: Boolean,
    val newMooRouter: Boolean,
    val newSubscriptionRegistry: Boolean,
    val newZoneStore: Boolean,
    val strictMooUnknownRequestIdDisconnect: Boolean
)

data class AppRuntimeConfig(
    val connection: ConnectionConfig,
    val discoveryNetwork: DiscoveryNetworkConfig,
    val discoveryPolicy: DiscoveryPolicyConfig,
    val discoveryTiming: DiscoveryTimingConfig,
    val uiTiming: UiTimingConfig,
    val cache: CacheConfig,
    val featureFlags: FeatureFlagConfig
) {
    companion object {
        fun defaults(): AppRuntimeConfig {
            val connectionConfig = ConnectionConfig(
                webSocketPort = 9330,
                tcpConnectTimeoutMs = 1000,
                webSocketConnectTimeoutMs = 5000,
                webSocketHandshakeTimeoutMs = 5000,
                webSocketReadTimeoutMs = 15000,
                smartRetryMaxAttempts = 5,
                smartRetryInitialDelayMs = 1000L,
                smartRetryMaxDelayMs = 15000L,
                healthCheckIntervalMs = 15000L,
                healthQuickCheckIntervalMs = 5000L,
                networkReadyTimeoutMs = 30000L,
                networkReadyPollIntervalMs = 1000L,
                networkConnectivityCheckTimeoutMs = 3000,
                networkTestHost = "8.8.8.8",
                networkTestPort = 53,
                autoConnectDelayMs = 1000L,
                autoDiscoveryDelayMs = 2000L,
                connectionHistoryRetentionMs = 30L * 24 * 60 * 60 * 1000,
                longPauseReconnectThresholdMs = 30000L,
                longStopReconnectThresholdMs = 60000L,
                smartReconnectMaxBackoffMs = 30000L
            )

            // 为什么通过统一工厂集中默认值：
            // 配置入口单一后，策略调整不需要在流程代码里追着改字面量，能显著降低回归风险。
            return AppRuntimeConfig(
                connection = connectionConfig,
                discoveryNetwork = DiscoveryNetworkConfig(
                    discoveryPort = 9003,
                    multicastGroup = "239.255.90.90",
                    soodServiceId = "00720724-5143-4a9b-abac-0e50cba674bb",
                    broadcastAddress = "255.255.255.255"
                ),
                discoveryPolicy = DiscoveryPolicyConfig.forRoonDefaults(
                    webSocketPort = connectionConfig.webSocketPort
                ),
                discoveryTiming = DiscoveryTimingConfig.defaults(),
                uiTiming = UiTimingConfig(
                    multiClickTimeDeltaMs = 400L,
                    singleClickDelayMs = 600L,
                    artWallUpdateIntervalMs = 60000L,
                    artWallStatsLogDelayMs = 3000L,
                    delayedArtWallSwitchDelayMs = 5000L,
                    resetDisplayArtWallDelayMs = 2000L,
                    startupUiSettleDelayMs = 2000L
                ),
                cache = CacheConfig(
                    maxCachedImages = 900,
                    maxDisplayCache = 15,
                    maxPreloadCache = 5,
                    memoryThresholdBytes = 50L * 1024 * 1024
                ),
                featureFlags = FeatureFlagConfig(
                    newSoodCodec = true,
                    newMooRouter = false,
                    newSubscriptionRegistry = false,
                    newZoneStore = false,
                    strictMooUnknownRequestIdDisconnect = false
                )
            )
        }
    }
}
