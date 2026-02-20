package com.example.roonplayer.state.transition

import kotlin.math.abs

sealed class ProgressSyncDecision {
    data class KeepPredicted(val positionMs: Long) : ProgressSyncDecision()

    data class SoftCatchUp(
        val predictedPositionMs: Long,
        val targetPositionMs: Long,
        val speedMultiplier: Float
    ) : ProgressSyncDecision()

    data class HardSnap(val positionMs: Long) : ProgressSyncDecision()
}

class ProgressSyncPolicy(
    private val softThresholdMs: Long = TrackTransitionDesignTokens.ProgressSync.SOFT_CATCH_UP_THRESHOLD_MS,
    private val hardThresholdMs: Long = TrackTransitionDesignTokens.ProgressSync.HARD_SNAP_THRESHOLD_MS,
    private val maxExtrapolationDriftMs: Long = TrackTransitionDesignTokens.ProgressSync.MAX_EXTRAPOLATION_DRIFT_MS,
    private val speedUpMultiplier: Float = TrackTransitionDesignTokens.ProgressSync.SOFT_CATCH_UP_SPEED_UP,
    private val slowDownMultiplier: Float = TrackTransitionDesignTokens.ProgressSync.SOFT_CATCH_UP_SLOW_DOWN
) {

    fun extrapolate(
        anchorPositionMs: Long,
        anchorRealtimeMs: Long,
        nowRealtimeMs: Long
    ): Long {
        val elapsedMs = (nowRealtimeMs - anchorRealtimeMs).coerceAtLeast(0L)
        return anchorPositionMs + elapsedMs.coerceAtMost(maxExtrapolationDriftMs)
    }

    fun reconcile(
        predictedPositionMs: Long,
        reportedPositionMs: Long
    ): ProgressSyncDecision {
        val drift = reportedPositionMs - predictedPositionMs
        val absoluteDrift = abs(drift)

        return when {
            absoluteDrift >= hardThresholdMs -> ProgressSyncDecision.HardSnap(reportedPositionMs)
            absoluteDrift >= softThresholdMs -> {
                val multiplier = if (drift > 0) speedUpMultiplier else slowDownMultiplier
                ProgressSyncDecision.SoftCatchUp(
                    predictedPositionMs = predictedPositionMs,
                    targetPositionMs = reportedPositionMs,
                    speedMultiplier = multiplier
                )
            }

            else -> ProgressSyncDecision.KeepPredicted(predictedPositionMs)
        }
    }
}
