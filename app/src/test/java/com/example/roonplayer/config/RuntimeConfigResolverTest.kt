package com.example.roonplayer.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigResolverTest {

    @Test
    fun `resolve falls back to defaults when no overrides`() {
        val defaults = AppRuntimeConfig.defaults()
        val result = RuntimeConfigResolver(defaults).resolve(
            overrides = emptyMap(),
            sourceName = "test"
        )

        assertEquals(defaults, result.config)
        assertTrue(result.overrides.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `resolve applies valid overrides with source tracking`() {
        val result = RuntimeConfigResolver().resolve(
            overrides = mapOf(
                RuntimeConfigKeys.CONNECTION_WEB_SOCKET_PORT to "9331",
                RuntimeConfigKeys.DISCOVERY_NETWORK_MULTICAST_GROUP to "239.255.90.91",
                RuntimeConfigKeys.UI_TIMING_SINGLE_CLICK_DELAY_MS to "750",
                RuntimeConfigKeys.FEATURE_NEW_MOO_ROUTER to "true"
            ),
            sourceName = "test"
        )

        assertEquals(9331, result.config.connection.webSocketPort)
        assertEquals("239.255.90.91", result.config.discoveryNetwork.multicastGroup)
        assertEquals(750L, result.config.uiTiming.singleClickDelayMs)
        assertEquals(true, result.config.featureFlags.newMooRouter)
        assertEquals(4, result.overrides.size)
        assertTrue(result.overrides.all { it.source == "test" })
    }

    @Test
    fun `resolve clamps out-of-range and reports validation warnings`() {
        val defaults = AppRuntimeConfig.defaults()
        val result = RuntimeConfigResolver(defaults).resolve(
            overrides = mapOf(
                RuntimeConfigKeys.CONNECTION_WEB_SOCKET_PORT to "70000",
                RuntimeConfigKeys.CONNECTION_SMART_RETRY_MAX_ATTEMPTS to "abc",
                RuntimeConfigKeys.DISCOVERY_NETWORK_MULTICAST_GROUP to "invalid_ip",
                RuntimeConfigKeys.FEATURE_NEW_ZONE_STORE to "invalid_bool"
            ),
            sourceName = "test"
        )

        assertEquals(65535, result.config.connection.webSocketPort)
        assertEquals(
            defaults.connection.smartRetryMaxAttempts,
            result.config.connection.smartRetryMaxAttempts
        )
        assertEquals(
            defaults.discoveryNetwork.multicastGroup,
            result.config.discoveryNetwork.multicastGroup
        )
        assertEquals(defaults.featureFlags.newZoneStore, result.config.featureFlags.newZoneStore)
        assertTrue(result.warnings.any { it.contains("clamped") })
        assertTrue(result.warnings.any { it.contains("Invalid int override") })
        assertTrue(result.warnings.any { it.contains("Invalid string override") })
        assertTrue(result.warnings.any { it.contains("Invalid boolean override") })
    }

    @Test
    fun `resolve clamps to minimum and maximum boundaries for extreme values`() {
        val result = RuntimeConfigResolver().resolve(
            overrides = mapOf(
                RuntimeConfigKeys.CONNECTION_NETWORK_READY_POLL_INTERVAL_MS to "1",
                RuntimeConfigKeys.CACHE_MEMORY_THRESHOLD_BYTES to "999999999999",
                RuntimeConfigKeys.CONNECTION_SMART_RETRY_MAX_ATTEMPTS to "999"
            ),
            sourceName = "test"
        )

        assertEquals(100L, result.config.connection.networkReadyPollIntervalMs)
        assertEquals(1_073_741_824L, result.config.cache.memoryThresholdBytes)
        assertEquals(20, result.config.connection.smartRetryMaxAttempts)
        assertTrue(result.warnings.any { it.contains("clamped") })
    }
}
