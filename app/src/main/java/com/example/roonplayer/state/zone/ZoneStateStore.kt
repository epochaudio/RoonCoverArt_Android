package com.example.roonplayer.state.zone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

data class ZoneStateSnapshot(
    val zones: Map<String, JSONObject>,
    val updatedAtMs: Long,
    val version: Long
)

class ZoneStateStore(
    private val reducer: ZoneReducer = ZoneReducer(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val stateRef = AtomicReference(
        ZoneStateSnapshot(
            zones = emptyMap(),
            updatedAtMs = nowMs(),
            version = 0L
        )
    )
    private val mutableUpdates = MutableStateFlow(stateRef.get())

    val updates: StateFlow<ZoneStateSnapshot> = mutableUpdates.asStateFlow()

    fun snapshot(): ZoneStateSnapshot {
        return stateRef.get()
    }

    fun apply(eventPayload: JSONObject): ZoneStateSnapshot {
        val previous = stateRef.get()
        val reduced = reducer.reduce(previous.zones, eventPayload)
        val next = ZoneStateSnapshot(
            zones = reduced,
            updatedAtMs = nowMs(),
            version = previous.version + 1
        )
        stateRef.set(next)
        mutableUpdates.value = next
        return next
    }

    fun reset(): ZoneStateSnapshot {
        val previous = stateRef.get()
        val next = ZoneStateSnapshot(
            zones = emptyMap(),
            updatedAtMs = nowMs(),
            version = previous.version + 1
        )
        stateRef.set(next)
        mutableUpdates.value = next
        return next
    }
}
