package com.example.roonplayer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneSelectionUseCaseTest {

    private val useCase = ZoneSelectionUseCase()

    @Test
    fun `returns null decision when zones are empty`() {
        val decision = useCase.selectZone(
            availableZones = emptyMap(),
            storedZoneId = null,
            currentZoneId = null
        )

        assertNull(decision.zoneId)
        assertEquals("无可用区域", decision.reason)
        assertFalse(decision.persist)
    }

    @Test
    fun `prefers stored zone when available`() {
        val zones = linkedMapOf(
            "zone_a" to zone(state = "paused"),
            "zone_b" to zone(state = "playing", nowPlaying = true)
        )

        val decision = useCase.selectZone(
            availableZones = zones,
            storedZoneId = "zone_a",
            currentZoneId = "zone_b"
        )

        assertEquals("zone_a", decision.zoneId)
        assertEquals("存储配置", decision.reason)
        assertFalse(decision.persist)
        assertNull(decision.statusMessage)
    }

    @Test
    fun `falls back when stored zone is unavailable`() {
        val zones = linkedMapOf(
            "zone_a" to zone(state = "stopped"),
            "zone_b" to zone(state = "playing", nowPlaying = true)
        )

        val decision = useCase.selectZone(
            availableZones = zones,
            storedZoneId = "missing_zone",
            currentZoneId = null
        )

        assertEquals("zone_b", decision.zoneId)
        assertEquals("配置失效回退", decision.reason)
        assertFalse(decision.persist)
        assertTrue(decision.statusMessage?.contains("回退") == true)
    }

    @Test
    fun `keeps current zone when valid and no stored zone`() {
        val zones = linkedMapOf(
            "zone_a" to zone(state = "paused", nowPlaying = true),
            "zone_b" to zone(state = "playing", nowPlaying = true)
        )

        val decision = useCase.selectZone(
            availableZones = zones,
            storedZoneId = null,
            currentZoneId = "zone_a"
        )

        assertEquals("zone_a", decision.zoneId)
        assertEquals("当前选择", decision.reason)
        assertFalse(decision.persist)
    }

    @Test
    fun `auto select prefers playing with now playing`() {
        val zones = linkedMapOf(
            "zone_a" to zone(state = "paused", nowPlaying = true),
            "zone_b" to zone(state = "playing", nowPlaying = true),
            "zone_c" to zone(state = "playing", nowPlaying = false)
        )

        val decision = useCase.selectZone(
            availableZones = zones,
            storedZoneId = null,
            currentZoneId = null
        )

        assertEquals("zone_b", decision.zoneId)
        assertEquals("自动选择", decision.reason)
        assertTrue(decision.persist)
    }

    @Test
    fun `auto select falls back to now playing then first zone`() {
        val zonesWithNowPlaying = linkedMapOf(
            "zone_a" to zone(state = "stopped"),
            "zone_b" to zone(state = "paused", nowPlaying = true)
        )
        val withNowPlayingDecision = useCase.selectZone(
            availableZones = zonesWithNowPlaying,
            storedZoneId = null,
            currentZoneId = null
        )
        assertEquals("zone_b", withNowPlayingDecision.zoneId)

        val zonesNoPlayback = linkedMapOf(
            "zone_x" to zone(state = "stopped"),
            "zone_y" to zone(state = "paused")
        )
        val noPlaybackDecision = useCase.selectZone(
            availableZones = zonesNoPlayback,
            storedZoneId = null,
            currentZoneId = null
        )
        assertEquals("zone_x", noPlaybackDecision.zoneId)
    }

    private fun zone(state: String, nowPlaying: Boolean = false): ZoneSnapshot {
        return ZoneSnapshot(
            state = state,
            hasNowPlaying = nowPlaying
        )
    }
}
