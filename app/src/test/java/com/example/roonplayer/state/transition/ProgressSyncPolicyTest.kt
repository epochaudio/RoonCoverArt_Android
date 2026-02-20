package com.example.roonplayer.state.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSyncPolicyTest {

    private val policy = ProgressSyncPolicy()

    @Test
    fun `small drift keeps predicted position`() {
        val decision = policy.reconcile(
            predictedPositionMs = 1_000L,
            reportedPositionMs = 1_040L
        )

        assertTrue(decision is ProgressSyncDecision.KeepPredicted)
        decision as ProgressSyncDecision.KeepPredicted
        assertEquals(1_000L, decision.positionMs)
    }

    @Test
    fun `medium drift triggers soft catch up`() {
        val decision = policy.reconcile(
            predictedPositionMs = 1_000L,
            reportedPositionMs = 1_160L
        )

        assertTrue(decision is ProgressSyncDecision.SoftCatchUp)
        decision as ProgressSyncDecision.SoftCatchUp
        assertEquals(1_000L, decision.predictedPositionMs)
        assertEquals(1_160L, decision.targetPositionMs)
        assertTrue(decision.speedMultiplier > 1.0f)
    }

    @Test
    fun `large drift triggers hard snap`() {
        val decision = policy.reconcile(
            predictedPositionMs = 1_000L,
            reportedPositionMs = 2_000L
        )

        assertTrue(decision is ProgressSyncDecision.HardSnap)
        decision as ProgressSyncDecision.HardSnap
        assertEquals(2_000L, decision.positionMs)
    }

    @Test
    fun `extrapolation clamps max drift`() {
        val predicted = policy.extrapolate(
            anchorPositionMs = 1_000L,
            anchorRealtimeMs = 10_000L,
            nowRealtimeMs = 12_000L
        )

        assertEquals(1_500L, predicted)
    }
}
