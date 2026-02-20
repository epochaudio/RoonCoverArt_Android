package com.example.roonplayer.state.transition

object TrackTransitionDesignTokens {

    object TransitionWindow {
        const val INTENT_MATCH_WINDOW_MS = 10_000L
    }

    object CoverTransition {
        const val SHIFT_DP = 36
        const val SCALE_DEPRESSION = 0.92f
        const val OUT_ALPHA = 0.78f
        const val OUT_SCALE = 0.96f
        const val OUT_DURATION_MS = 140L
        const val IN_DURATION_MS = 210L
        const val HANDOFF_DELAY_MS = 150L
        const val RETURN_OVERSHOOT_SCALE = 1.02f

        object Interpolator {
            const val EXIT_X1 = 0.4f
            const val EXIT_Y1 = 0.0f
            const val EXIT_X2 = 1.0f
            const val EXIT_Y2 = 1.0f

            const val SOFT_SPRING_X1 = 0.34f
            const val SOFT_SPRING_Y1 = 1.56f
            const val SOFT_SPRING_X2 = 0.64f
            const val SOFT_SPRING_Y2 = 1.0f
        }
    }

    object TextTransition {
        const val SLOT_SHIFT_DP = 18
        const val SLOT_SHIFT_ROLLBACK_DP = 14
        const val OUT_ALPHA = 0.25f
        const val OUT_DURATION_MS = 150L
        const val IN_DURATION_MS = 250L
        const val STAGGER_DELAY_MS = 30L
    }

    object DirectionVectors {
        const val VECTOR_NEXT = -1f
        const val VECTOR_PREVIOUS = 1f
    }

    object Rollback {
        const val DURATION_MS = 180L
        const val TINT_BLEND_RATIO = 0.20f
        const val TINT_IN_DURATION_MS = 90L
        const val TINT_OUT_DURATION_MS = 220L
        const val TINT_COLOR = 0xFF8E3A34.toInt()
    }

    object SwipeFeedback {
        const val SHIFT_DP = 24
        const val OUT_SCALE = 0.985f
        const val OUT_DURATION_MS = 90L
        const val IN_DURATION_MS = 150L
    }

    object CoverDrag {
        const val PRESS_DURATION_MS = 100L
        const val RELEASE_OUT_DURATION_MS = 90L
        const val RELEASE_IN_DURATION_SENT_MS = 170L
        const val RELEASE_IN_DURATION_CANCEL_MS = 130L
        const val PREVIEW_HIDE_DURATION_MS = 120L
    }

    object Palette {
        const val COLOR_TRANSITION_DURATION_MS = 260L
        const val COLOR_TRANSITION_START_DELAY_MS = 36L
        const val SHADOW_FADE_OUT_MS = 150L
        const val SHADOW_FADE_IN_MS = 300L
    }

    object ProgressSync {
        const val SOFT_CATCH_UP_THRESHOLD_MS = 100L
        const val HARD_SNAP_THRESHOLD_MS = 500L
        const val MAX_EXTRAPOLATION_DRIFT_MS = 500L
        const val SOFT_CATCH_UP_SPEED_UP = 1.05f
        const val SOFT_CATCH_UP_SLOW_DOWN = 0.95f
    }
}
