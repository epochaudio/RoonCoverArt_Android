package com.example.roonplayer.state.transition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTransitionStoreTest {

    @Test
    fun `store processes intents sequentially and keeps latest intent semantics`() = runBlocking {
        val reducer = TrackTransitionReducer()
        val observedEffects = mutableListOf<TrackTransitionEffect>()
        val handler = TrackTransitionEffectHandler { effect ->
            synchronized(observedEffects) {
                observedEffects.add(effect)
            }
        }

        val committed = TransitionTrack(
            id = "track_a",
            title = "A",
            artist = "Artist",
            album = "Album"
        )
        val initial = TrackTransitionState.initial("session-1").copy(
            currentKey = CorrelationKey("session-1", queueVersion = 9L, intentId = 0L),
            committedTrack = committed,
            displayTrack = committed,
            audioReady = true
        )

        val store = TrackTransitionStore(
            initialState = initial,
            reducer = reducer,
            effectHandler = handler,
            dispatcher = Dispatchers.Default
        )

        try {
            val key1 = CorrelationKey("session-1", queueVersion = 9L, intentId = 1L)
            val key2 = CorrelationKey("session-1", queueVersion = 9L, intentId = 2L)
            val trackB = committed.copy(id = "track_b", title = "B")
            val trackC = committed.copy(id = "track_c", title = "C")

            store.dispatch(
                TrackTransitionIntent.Skip(
                    key = key1,
                    direction = TransitionDirection.NEXT,
                    targetTrack = trackB
                )
            )
            store.dispatch(
                TrackTransitionIntent.Skip(
                    key = key2,
                    direction = TransitionDirection.NEXT,
                    targetTrack = trackC
                )
            )
            store.dispatch(
                TrackTransitionIntent.EngineUpdate(
                    EngineEvent.Playing(
                        key = key1,
                        track = trackB,
                        anchorPositionMs = 1_000L,
                        anchorRealtimeMs = 2_000L
                    )
                )
            )
            store.dispatch(
                TrackTransitionIntent.EngineUpdate(
                    EngineEvent.Playing(
                        key = key2,
                        track = trackC,
                        anchorPositionMs = 2_000L,
                        anchorRealtimeMs = 3_000L
                    )
                )
            )

            delay(100)
            val state = store.state.value
            assertEquals(key2, state.currentKey)
            assertEquals("track_c", state.committedTrack?.id)
            assertEquals("track_c", state.displayTrack?.id)

            synchronized(observedEffects) {
                assertTrue(observedEffects.isNotEmpty())
            }
        } finally {
            store.close()
        }
    }
}
