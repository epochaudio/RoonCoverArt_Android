package com.example.roonplayer.state.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionAnimationSessionTest {

    @Test
    fun `handoff commit is idempotent`() {
        val session = newSession()
        var commits = 0

        val first = session.commitHandoffOnce { commits += 1 }
        val second = session.commitHandoffOnce { commits += 1 }

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, commits)
    }

    @Test
    fun `field commit is idempotent per field id`() {
        val session = newSession()
        var commits = 0

        val trackFirst = session.commitFieldOnce("track") { commits += 1 }
        val trackSecond = session.commitFieldOnce("track") { commits += 1 }
        val artistFirst = session.commitFieldOnce("artist") { commits += 1 }

        assertTrue(trackFirst)
        assertFalse(trackSecond)
        assertTrue(artistFirst)
        assertEquals(2, commits)
    }

    private fun newSession(): TransitionAnimationSession {
        return TransitionAnimationSession(
            sessionId = 1L,
            key = CorrelationKey("session-1", queueVersion = 1L, intentId = 1L),
            phase = UiPhase.OPTIMISTIC_MORPHING,
            direction = TransitionDirection.NEXT,
            targetTrack = TransitionTrack(
                id = "track_1",
                title = "Track 1",
                artist = "Artist 1",
                album = "Album 1"
            ),
            startedAtMs = 0L
        )
    }
}
