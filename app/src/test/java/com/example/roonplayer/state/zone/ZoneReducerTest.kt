package com.example.roonplayer.state.zone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneReducerTest {

    private val reducer = ZoneReducer()

    @Test
    fun `reduce applies zones added changed and removed sequence`() {
        val initial = mapOf(
            "zone_a" to JSONObject().apply {
                put("zone_id", "zone_a")
                put("state", "paused")
                put("display_name", "Living Room")
            }
        )

        val event = JSONObject().apply {
            put("zones_added", JSONArray().put(JSONObject().apply {
                put("zone_id", "zone_b")
                put("state", "playing")
                put("display_name", "Bedroom")
            }))
            put("zones_changed", JSONArray().put(JSONObject().apply {
                put("zone_id", "zone_a")
                put("state", "playing")
            }))
            put("zones_removed", JSONArray().put(JSONObject().apply {
                put("zone_id", "zone_b")
            }))
        }

        val reduced = reducer.reduce(initial, event)

        assertEquals(1, reduced.size)
        assertEquals("playing", reduced["zone_a"]?.optString("state"))
        assertTrue(reduced.containsKey("zone_a"))
        assertFalse(reduced.containsKey("zone_b"))
    }

    @Test
    fun `reduce seek only event keeps now playing metadata`() {
        val initial = mapOf(
            "zone_a" to JSONObject().apply {
                put("zone_id", "zone_a")
                put("state", "playing")
                put("now_playing", JSONObject().apply {
                    put("three_line", JSONObject().apply {
                        put("line1", "Track A")
                    })
                })
            }
        )

        val seekEvent = JSONObject().apply {
            put("zones_seek_changed", JSONArray().put(JSONObject().apply {
                put("zone_id", "zone_a")
                put("seek_position", 128000)
            }))
        }

        val reduced = reducer.reduce(initial, seekEvent)
        val nowPlaying = reduced["zone_a"]?.optJSONObject("now_playing")

        assertEquals("Track A", nowPlaying?.optJSONObject("three_line")?.optString("line1"))
        assertEquals(128000, reduced["zone_a"]?.optInt("seek_position"))
    }

    @Test
    fun `reduce full zones payload replaces previous snapshot`() {
        val initial = mapOf(
            "zone_old" to JSONObject().apply {
                put("zone_id", "zone_old")
            }
        )

        val fullPayload = JSONObject().apply {
            put("zones", JSONArray().put(JSONObject().apply {
                put("zone_id", "zone_new")
                put("state", "playing")
            }))
        }

        val reduced = reducer.reduce(initial, fullPayload)

        assertEquals(1, reduced.size)
        assertTrue(reduced.containsKey("zone_new"))
        assertFalse(reduced.containsKey("zone_old"))
    }
}
