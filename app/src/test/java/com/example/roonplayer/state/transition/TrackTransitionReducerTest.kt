package com.example.roonplayer.state.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTransitionReducerTest {

    private val reducer = TrackTransitionReducer()

    @Test
    fun `skip intent enters optimistic morphing and emits engine command`() {
        val committed = track("track_a")
        val target = track("track_b")
        val key = CorrelationKey("session-1", queueVersion = 4L, intentId = 8L)
        val initial = initialState(committed)

        val reduction = reducer.reduce(
            previous = initial,
            intent = TrackTransitionIntent.Skip(
                key = key,
                direction = TransitionDirection.NEXT,
                targetTrack = target
            )
        )

        assertEquals(UiPhase.OPTIMISTIC_MORPHING, reduction.state.phase)
        assertEquals(1, reduction.state.activeTransitionCount)
        assertEquals(target, reduction.state.displayTrack)
        assertEquals(target, reduction.state.optimisticTrack)

        val command = reduction.effects.single() as TrackTransitionEffect.CommandEngine
        assertEquals(key, command.correlationKey)
        assertEquals(EngineCommand.SKIP_NEXT, command.command)
        assertEquals(target, command.track)
    }

    @Test
    fun `stale engine callback is dropped`() {
        val trackA = track("track_a")
        val trackB = track("track_b")
        val initial = initialState(trackA)
        val currentKey = CorrelationKey("session-1", queueVersion = 1L, intentId = 100L)
        val current = reducer.reduce(
            previous = initial,
            intent = TrackTransitionIntent.Skip(
                key = currentKey,
                direction = TransitionDirection.NEXT,
                targetTrack = trackB
            )
        ).state

        val staleEvent = TrackTransitionIntent.EngineUpdate(
            EngineEvent.Playing(
                key = CorrelationKey("session-1", queueVersion = 1L, intentId = 99L),
                track = trackA,
                anchorPositionMs = 900L,
                anchorRealtimeMs = 1800L
            )
        )

        val reduction = reducer.reduce(current, staleEvent)

        assertEquals(current, reduction.state)
        assertTrue(reduction.effects.isEmpty())
    }

    @Test
    fun `last intent wins when old request returns late`() {
        val trackA = track("track_a")
        val trackB = track("track_b")
        val trackC = track("track_c")

        val state0 = initialState(trackA)
        val key1 = CorrelationKey("session-1", queueVersion = 5L, intentId = 1L)
        val state1 = reducer.reduce(
            state0,
            TrackTransitionIntent.Skip(
                key = key1,
                direction = TransitionDirection.NEXT,
                targetTrack = trackB
            )
        ).state

        val key2 = CorrelationKey("session-1", queueVersion = 5L, intentId = 2L)
        val state2 = reducer.reduce(
            state1,
            TrackTransitionIntent.Skip(
                key = key2,
                direction = TransitionDirection.NEXT,
                targetTrack = trackC
            )
        ).state

        val lateOldResponse = TrackTransitionIntent.EngineUpdate(
            EngineEvent.Playing(
                key = key1,
                track = trackB,
                anchorPositionMs = 1000L,
                anchorRealtimeMs = 2000L
            )
        )

        val reduced = reducer.reduce(state2, lateOldResponse)

        assertEquals(state2, reduced.state)
    }

    @Test
    fun `error rolls back to committed track`() {
        val committed = track("track_a")
        val target = track("track_b")
        val key = CorrelationKey("session-1", queueVersion = 3L, intentId = 11L)

        val optimistic = reducer.reduce(
            initialState(committed),
            TrackTransitionIntent.Skip(
                key = key,
                direction = TransitionDirection.NEXT,
                targetTrack = target
            )
        ).state

        val reduced = reducer.reduce(
            optimistic,
            TrackTransitionIntent.EngineUpdate(
                EngineEvent.Error(
                    key = key,
                    failedTrack = target,
                    failure = PlaybackFailure(
                        category = PlaybackFailureCategory.TIMEOUT,
                        message = "timeout"
                    )
                )
            )
        )

        assertEquals(UiPhase.ROLLING_BACK, reduced.state.phase)
        assertEquals(committed, reduced.state.displayTrack)
        assertNull(reduced.state.optimisticTrack)
        assertEquals(1, reduced.state.activeTransitionCount)
    }

    @Test
    fun `animation completion can move optimistic flow into awaiting engine`() {
        val committed = track("track_a")
        val target = track("track_b")
        val key = CorrelationKey("session-1", queueVersion = 2L, intentId = 5L)

        val optimistic = reducer.reduce(
            initialState(committed),
            TrackTransitionIntent.Skip(
                key = key,
                direction = TransitionDirection.NEXT,
                targetTrack = target
            )
        ).state

        val reduced = reducer.reduce(
            optimistic,
            TrackTransitionIntent.AnimationCompleted(key)
        )

        assertEquals(UiPhase.AWAITING_ENGINE, reduced.state.phase)
        assertEquals(0, reduced.state.activeTransitionCount)
        assertEquals(target, reduced.state.optimisticTrack)
    }

    @Test(expected = IllegalStateException::class)
    fun `invariants reject invalid display source`() {
        val committed = track("track_a")
        val invalid = TrackTransitionState(
            currentKey = CorrelationKey("session-1", queueVersion = 1L, intentId = 10L),
            committedTrack = committed,
            displayTrack = track("stray"),
            optimisticTrack = null,
            phase = UiPhase.STABLE,
            transitionDirection = TransitionDirection.UNKNOWN,
            audioReady = true,
            activeTransitionCount = 0
        )

        reducer.reduce(
            invalid,
            TrackTransitionIntent.EngineUpdate(
                EngineEvent.Playing(
                    key = CorrelationKey("session-1", queueVersion = 1L, intentId = 9L),
                    track = committed,
                    anchorPositionMs = 0L,
                    anchorRealtimeMs = 0L
                )
            )
        )
    }

    private fun initialState(committed: TransitionTrack): TrackTransitionState {
        return TrackTransitionState.initial(sessionId = "session-1").copy(
            currentKey = CorrelationKey("session-1", queueVersion = 1L, intentId = 0L),
            committedTrack = committed,
            displayTrack = committed,
            optimisticTrack = null,
            phase = UiPhase.STABLE,
            audioReady = true,
            activeTransitionCount = 0
        )
    }

    private fun track(id: String): TransitionTrack {
        return TransitionTrack(
            id = id,
            title = "title_$id",
            artist = "artist_$id",
            album = "album_$id",
            imageKey = "image_$id"
        )
    }
}
