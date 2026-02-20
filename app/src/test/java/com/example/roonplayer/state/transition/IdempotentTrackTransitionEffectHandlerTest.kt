package com.example.roonplayer.state.transition

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class IdempotentTrackTransitionEffectHandlerTest {

    @Test
    fun `duplicate engine effect is handled once`() = runBlocking {
        var calls = 0
        val delegate = TrackTransitionEffectHandler {
            calls += 1
        }
        val handler = IdempotentTrackTransitionEffectHandler(delegate)

        val effect = TrackTransitionEffect.CommandEngine(
            correlationKey = CorrelationKey("session-1", queueVersion = 1L, intentId = 7L),
            command = EngineCommand.SKIP_NEXT,
            track = TransitionTrack(
                id = "track_a",
                title = "A",
                artist = "Artist",
                album = "Album"
            )
        )

        handler.handle(effect)
        handler.handle(effect)

        assertEquals(1, calls)
    }

    @Test
    fun `different metric names are not deduplicated`() = runBlocking {
        var calls = 0
        val delegate = TrackTransitionEffectHandler {
            calls += 1
        }
        val handler = IdempotentTrackTransitionEffectHandler(delegate)
        val key = CorrelationKey("session-1", queueVersion = 1L, intentId = 4L)

        handler.handle(TrackTransitionEffect.EmitMetric(correlationKey = key, name = "tap_to_visual"))
        handler.handle(TrackTransitionEffect.EmitMetric(correlationKey = key, name = "tap_to_sound"))

        assertEquals(2, calls)
    }
}
