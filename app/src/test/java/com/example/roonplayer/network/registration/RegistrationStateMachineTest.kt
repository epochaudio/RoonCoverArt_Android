package com.example.roonplayer.network.registration

import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationStateMachineTest {

    @Test
    fun `state machine transitions from info to registered`() {
        val machine = RegistrationStateMachine()

        machine.onInfoRequested()
        assertEquals(RegistrationState.FetchingInfo, machine.snapshot().state)

        machine.onInfoReceived("core_a")
        assertEquals(RegistrationState.Registering, machine.snapshot().state)
        assertEquals("core_a", machine.snapshot().coreId)

        machine.onRegistrationSucceeded("token_a")
        assertEquals(RegistrationState.Registered, machine.snapshot().state)
        assertEquals("token_a", machine.snapshot().token)
    }

    @Test
    fun `state machine supports waiting approval and failure`() {
        val machine = RegistrationStateMachine()

        machine.onWaitingApproval()
        assertEquals(RegistrationState.WaitingApproval, machine.snapshot().state)

        machine.onFailure("network_error")
        assertEquals(RegistrationState.Failed, machine.snapshot().state)
        assertEquals("network_error", machine.snapshot().error)
    }
}
