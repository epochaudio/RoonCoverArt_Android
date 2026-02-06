package com.example.roonplayer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRoutingUseCaseTest {

    private val useCase = ConnectionRoutingUseCase()

    @Test
    fun `discovery startup prefers latest paired core`() {
        val strategy = useCase.strategyForDiscoveryStartup(
            pairedCores = listOf(
                PairedCoreSnapshot(host = "192.168.1.10", port = 9330, lastConnected = 100),
                PairedCoreSnapshot(host = "192.168.1.20", port = 9330, lastConnected = 200)
            )
        )

        assertTrue(strategy is ConnectionRecoveryStrategy.Connect)
        val connect = strategy as ConnectionRecoveryStrategy.Connect
        assertEquals("192.168.1.20", connect.target.host)
        assertEquals(9330, connect.target.port)
    }

    @Test
    fun `discovery startup falls back to discover when no paired cores`() {
        val strategy = useCase.strategyForDiscoveryStartup(emptyList())
        assertEquals(ConnectionRecoveryStrategy.Discover, strategy)
    }

    @Test
    fun `auto reconnection returns no-op when already attempted`() {
        val strategy = useCase.strategyForAutoReconnection(
            autoReconnectAlreadyAttempted = true,
            pairedCores = listOf(
                PairedCoreSnapshot(host = "192.168.1.10", port = 9330, lastConnected = 100)
            )
        )

        assertEquals(ConnectionRecoveryStrategy.NoOp, strategy)
    }

    @Test
    fun `auto reconnection returns no-op when paired core list is empty`() {
        val strategy = useCase.strategyForAutoReconnection(
            autoReconnectAlreadyAttempted = false,
            pairedCores = emptyList()
        )

        assertEquals(ConnectionRecoveryStrategy.NoOp, strategy)
    }

    @Test
    fun `auto reconnection connects to latest paired core when available`() {
        val strategy = useCase.strategyForAutoReconnection(
            autoReconnectAlreadyAttempted = false,
            pairedCores = listOf(
                PairedCoreSnapshot(host = "192.168.1.10", port = 9330, lastConnected = 100),
                PairedCoreSnapshot(host = "192.168.1.20", port = 9331, lastConnected = 300)
            )
        )

        assertTrue(strategy is ConnectionRecoveryStrategy.Connect)
        val connect = strategy as ConnectionRecoveryStrategy.Connect
        assertEquals("192.168.1.20", connect.target.host)
        assertEquals(9331, connect.target.port)
    }
}
