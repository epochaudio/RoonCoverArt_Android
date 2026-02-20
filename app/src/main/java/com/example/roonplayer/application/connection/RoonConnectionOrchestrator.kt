package com.example.roonplayer.application.connection

import com.example.roonplayer.state.queue.QueueStateSnapshot
import com.example.roonplayer.state.queue.QueueStore
import com.example.roonplayer.state.zone.ZoneStateSnapshot
import com.example.roonplayer.state.zone.ZoneStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RoonConnectionState {
    Disconnected,
    Discovering,
    Connecting,
    Registering,
    WaitingApproval,
    Connected,
    Reconnecting,
    Failed
}

/**
 * Application-level orchestrator. UI should consume these state streams
 * instead of protocol-specific internals.
 */
class RoonConnectionOrchestrator(
    private val zoneStateStore: ZoneStateStore,
    private val queueStore: QueueStore
) {
    private val mutableConnectionState = MutableStateFlow(RoonConnectionState.Disconnected)
    private val mutableLastError = MutableStateFlow<String?>(null)

    val connectionState: StateFlow<RoonConnectionState> = mutableConnectionState.asStateFlow()
    val zoneSnapshot: StateFlow<ZoneStateSnapshot> = zoneStateStore.updates
    val lastError: StateFlow<String?> = mutableLastError.asStateFlow()

    fun queueSnapshot(): QueueStateSnapshot {
        return queueStore.snapshot()
    }

    fun transition(
        state: RoonConnectionState,
        error: String? = null
    ) {
        mutableConnectionState.value = state
        mutableLastError.value = error
    }
}
