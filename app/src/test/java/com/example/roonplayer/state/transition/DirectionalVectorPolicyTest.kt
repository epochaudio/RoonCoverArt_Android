package com.example.roonplayer.state.transition

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionalVectorPolicyTest {

    @Test
    fun `next motion uses forward vector and cascade`() {
        val motion = DirectionalVectorPolicy.resolve(
            phase = UiPhase.OPTIMISTIC_MORPHING,
            direction = TransitionDirection.NEXT
        )

        assertEquals(TrackTransitionDesignTokens.DirectionVectors.VECTOR_NEXT, motion.vector)
        assertEquals(
            listOf(TextCascadeField.TRACK, TextCascadeField.ARTIST, TextCascadeField.ALBUM),
            motion.cascade
        )
    }

    @Test
    fun `previous motion uses backward vector and cascade`() {
        val motion = DirectionalVectorPolicy.resolve(
            phase = UiPhase.OPTIMISTIC_MORPHING,
            direction = TransitionDirection.PREVIOUS
        )

        assertEquals(TrackTransitionDesignTokens.DirectionVectors.VECTOR_PREVIOUS, motion.vector)
        assertEquals(
            listOf(TextCascadeField.ALBUM, TextCascadeField.ARTIST, TextCascadeField.TRACK),
            motion.cascade
        )
    }

    @Test
    fun `rollback reverses forward vector and cascade`() {
        val motion = DirectionalVectorPolicy.resolve(
            phase = UiPhase.ROLLING_BACK,
            direction = TransitionDirection.NEXT
        )

        assertEquals(TrackTransitionDesignTokens.DirectionVectors.VECTOR_PREVIOUS, motion.vector)
        assertEquals(
            listOf(TextCascadeField.ALBUM, TextCascadeField.ARTIST, TextCascadeField.TRACK),
            motion.cascade
        )
    }
}
