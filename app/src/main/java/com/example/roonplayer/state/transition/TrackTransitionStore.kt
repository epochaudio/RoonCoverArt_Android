package com.example.roonplayer.state.transition

import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single-writer event loop. All intents are serialized through one channel.
 */
class TrackTransitionStore(
    initialState: TrackTransitionState,
    private val reducer: TrackTransitionReducer,
    private val effectHandler: TrackTransitionEffectHandler,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val intentChannel = Channel<TrackTransitionIntent>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(initialState)

    val state: StateFlow<TrackTransitionState> = mutableState.asStateFlow()

    init {
        scope.launch {
            for (intent in intentChannel) {
                val reduction = reducer.reduce(mutableState.value, intent)
                mutableState.value = reduction.state
                reduction.effects.forEach { effect ->
                    effectHandler.handle(effect)
                }
            }
        }
    }

    suspend fun dispatch(intent: TrackTransitionIntent) {
        intentChannel.send(intent)
    }

    fun tryDispatch(intent: TrackTransitionIntent): Boolean {
        return intentChannel.trySend(intent).isSuccess
    }

    override fun close() {
        intentChannel.close()
        scope.cancel()
    }
}
