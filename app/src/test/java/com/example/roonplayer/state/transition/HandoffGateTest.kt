package com.example.roonplayer.state.transition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffGateTest {

    @Test
    fun `canCommit returns true only when key matches active key`() {
        var activeKey = CorrelationKey("session-1", queueVersion = 1L, intentId = 1L)
        val gate = HandoffGate(activeKeyProvider = { activeKey })

        assertTrue(gate.canCommit(activeKey))

        val staleKey = CorrelationKey("session-1", queueVersion = 1L, intentId = 0L)
        assertFalse(gate.canCommit(staleKey))

        activeKey = CorrelationKey("session-1", queueVersion = 2L, intentId = 1L)
        assertFalse(gate.canCommit(CorrelationKey("session-1", queueVersion = 1L, intentId = 1L)))
    }
}
