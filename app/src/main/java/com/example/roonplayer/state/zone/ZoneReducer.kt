package com.example.roonplayer.state.zone

import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies transport zone delta semantics to a mutable zone map.
 */
class ZoneReducer {

    fun reduce(
        previous: Map<String, JSONObject>,
        eventPayload: JSONObject
    ): Map<String, JSONObject> {
        val next = LinkedHashMap<String, JSONObject>()
        for ((zoneId, zone) in previous) {
            next[zoneId] = cloneJson(zone)
        }

        val fullZones = eventPayload.optJSONArray(KEY_ZONES)
        if (fullZones != null) {
            next.clear()
            applyUpsertArray(next, fullZones)
        }

        applyUpsertArray(next, eventPayload.optJSONArray(KEY_ZONES_ADDED))
        applyPatchArray(next, eventPayload.optJSONArray(KEY_ZONES_CHANGED))
        applyPatchArray(next, eventPayload.optJSONArray(KEY_ZONES_STATE_CHANGED))
        applyPatchArray(next, eventPayload.optJSONArray(KEY_ZONES_NOW_PLAYING_CHANGED))
        applyPatchArray(next, eventPayload.optJSONArray(KEY_ZONES_SEEK_CHANGED))
        applyRemovedArray(next, eventPayload.optJSONArray(KEY_ZONES_REMOVED))

        return next
    }

    private fun applyUpsertArray(
        target: MutableMap<String, JSONObject>,
        array: JSONArray?
    ) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val zone = array.optJSONObject(i) ?: continue
            val zoneId = zone.optString(KEY_ZONE_ID).takeIf { it.isNotBlank() } ?: continue
            target[zoneId] = cloneJson(zone)
        }
    }

    private fun applyPatchArray(
        target: MutableMap<String, JSONObject>,
        array: JSONArray?
    ) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val patch = array.optJSONObject(i) ?: continue
            val zoneId = patch.optString(KEY_ZONE_ID).takeIf { it.isNotBlank() } ?: continue
            val base = target[zoneId]
            target[zoneId] = if (base == null) {
                cloneJson(patch)
            } else {
                mergeJson(base, patch)
            }
        }
    }

    private fun applyRemovedArray(
        target: MutableMap<String, JSONObject>,
        array: JSONArray?
    ) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val removed = array.optJSONObject(i)
            val zoneId = removed?.optString(KEY_ZONE_ID)
                ?.takeIf { it.isNotBlank() }
                ?: continue
            target.remove(zoneId)
        }
    }

    private fun mergeJson(base: JSONObject, patch: JSONObject): JSONObject {
        val merged = cloneJson(base)
        val keys = patch.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val patchValue = patch.opt(key)
            val baseValue = merged.opt(key)

            if (patchValue is JSONObject && baseValue is JSONObject) {
                merged.put(key, mergeJson(baseValue, patchValue))
            } else {
                merged.put(key, patchValue)
            }
        }
        return merged
    }

    private fun cloneJson(source: JSONObject): JSONObject {
        return JSONObject(source.toString())
    }

    companion object {
        private const val KEY_ZONE_ID = "zone_id"
        private const val KEY_ZONES = "zones"
        private const val KEY_ZONES_ADDED = "zones_added"
        private const val KEY_ZONES_CHANGED = "zones_changed"
        private const val KEY_ZONES_REMOVED = "zones_removed"
        private const val KEY_ZONES_STATE_CHANGED = "zones_state_changed"
        private const val KEY_ZONES_NOW_PLAYING_CHANGED = "zones_now_playing_changed"
        private const val KEY_ZONES_SEEK_CHANGED = "zones_seek_changed"
    }
}
