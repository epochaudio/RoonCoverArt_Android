package com.example.roonplayer.state.transition

import android.content.SharedPreferences
import org.json.JSONObject

class SharedPreferencesCommittedPlaybackSnapshotRepository(
    private val sharedPreferences: SharedPreferences,
    private val key: String = "track_transition_committed_snapshot"
) : CommittedPlaybackSnapshotRepository {

    override fun read(): CommittedPlaybackSnapshot? {
        val raw = sharedPreferences.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            CommittedPlaybackSnapshot(
                sessionId = json.getString("session_id"),
                queueVersion = json.getLong("queue_version"),
                track = TransitionTrack(
                    id = json.getString("track_id"),
                    title = json.getString("track_title"),
                    artist = json.getString("track_artist"),
                    album = json.getString("track_album"),
                    imageKey = json.optString("image_key").takeIf { it.isNotBlank() }
                ),
                anchorPositionMs = json.getLong("anchor_position_ms"),
                anchorRealtimeMs = json.getLong("anchor_realtime_ms")
            )
        }.getOrNull()
    }

    override fun write(snapshot: CommittedPlaybackSnapshot) {
        val json = JSONObject().apply {
            put("session_id", snapshot.sessionId)
            put("queue_version", snapshot.queueVersion)
            put("track_id", snapshot.track.id)
            put("track_title", snapshot.track.title)
            put("track_artist", snapshot.track.artist)
            put("track_album", snapshot.track.album)
            put("image_key", snapshot.track.imageKey ?: "")
            put("anchor_position_ms", snapshot.anchorPositionMs)
            put("anchor_realtime_ms", snapshot.anchorRealtimeMs)
        }
        sharedPreferences.edit().putString(key, json.toString()).apply()
    }
}
