package com.example.roonplayer.state.queue

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

data class QueueStateSnapshot(
    val currentZoneId: String?,
    val payload: JSONObject?,
    val updatedAtMs: Long,
    val version: Long
)

class QueueStore(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val stateRef = AtomicReference(
        QueueStateSnapshot(
            currentZoneId = null,
            payload = null,
            updatedAtMs = nowMs(),
            version = 0L
        )
    )

    fun snapshot(): QueueStateSnapshot {
        return stateRef.get()
    }

    fun setCurrentZone(zoneId: String?): QueueStateSnapshot {
        val previous = stateRef.get()
        if (previous.currentZoneId == zoneId) {
            return previous
        }
        val next = QueueStateSnapshot(
            currentZoneId = zoneId,
            payload = null,
            updatedAtMs = nowMs(),
            version = previous.version + 1
        )
        stateRef.set(next)
        return next
    }

    fun updateIfMatchesCurrentZone(
        zoneId: String?,
        payload: JSONObject
    ): QueueStateSnapshot? {
        val current = stateRef.get()
        if (!current.currentZoneId.isNullOrBlank() && zoneId != null && zoneId != current.currentZoneId) {
            return null
        }
        val next = QueueStateSnapshot(
            currentZoneId = current.currentZoneId,
            payload = JSONObject(payload.toString()),
            updatedAtMs = nowMs(),
            version = current.version + 1
        )
        stateRef.set(next)
        return next
    }

    fun clear(): QueueStateSnapshot {
        val previous = stateRef.get()
        val next = QueueStateSnapshot(
            currentZoneId = previous.currentZoneId,
            payload = null,
            updatedAtMs = nowMs(),
            version = previous.version + 1
        )
        stateRef.set(next)
        return next
    }
}
