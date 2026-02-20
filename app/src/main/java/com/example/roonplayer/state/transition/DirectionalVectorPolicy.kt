package com.example.roonplayer.state.transition

enum class TextCascadeField {
    TRACK,
    ARTIST,
    ALBUM
}

data class DirectionalMotion(
    val vector: Float,
    val cascade: List<TextCascadeField>
)

object DirectionalVectorPolicy {

    private val forwardCascade = listOf(
        TextCascadeField.TRACK,
        TextCascadeField.ARTIST,
        TextCascadeField.ALBUM
    )

    private val backwardCascade = listOf(
        TextCascadeField.ALBUM,
        TextCascadeField.ARTIST,
        TextCascadeField.TRACK
    )

    fun resolve(
        phase: UiPhase,
        direction: TransitionDirection
    ): DirectionalMotion {
        return when (phase) {
            UiPhase.ROLLING_BACK -> resolveRollback(direction)
            UiPhase.OPTIMISTIC_MORPHING,
            UiPhase.AWAITING_ENGINE,
            UiPhase.STABLE -> resolveForward(direction)
        }
    }

    private fun resolveForward(direction: TransitionDirection): DirectionalMotion {
        return when (direction) {
            TransitionDirection.NEXT -> {
                DirectionalMotion(
                    vector = TrackTransitionDesignTokens.DirectionVectors.VECTOR_NEXT,
                    cascade = forwardCascade
                )
            }

            TransitionDirection.PREVIOUS -> {
                DirectionalMotion(
                    vector = TrackTransitionDesignTokens.DirectionVectors.VECTOR_PREVIOUS,
                    cascade = backwardCascade
                )
            }

            TransitionDirection.UNKNOWN -> {
                DirectionalMotion(
                    vector = TrackTransitionDesignTokens.DirectionVectors.VECTOR_NEXT,
                    cascade = forwardCascade
                )
            }
        }
    }

    private fun resolveRollback(direction: TransitionDirection): DirectionalMotion {
        val forward = resolveForward(direction)
        return DirectionalMotion(
            vector = -forward.vector,
            cascade = forward.cascade.reversed()
        )
    }
}
