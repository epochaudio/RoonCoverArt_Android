package com.example.roonplayer.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProbeUseCaseTest {

    private val useCase = ConnectionProbeUseCase()

    @Test
    fun `firstMatchFromSavedConnections returns first reachable and short-circuits`() = runBlocking {
        val visited = mutableListOf<ProbeTarget>()
        val result = useCase.firstMatchFromSavedConnections(
            savedConnections = listOf(
                "192.168.1.10" to 9330,
                "192.168.1.20" to 9330,
                "192.168.1.30" to 9330
            )
        ) { target ->
            visited += target
            target.ip == "192.168.1.20"
        }

        assertEquals(ProbeTarget("192.168.1.20", 9330), result)
        assertEquals(
            listOf(
                ProbeTarget("192.168.1.10", 9330),
                ProbeTarget("192.168.1.20", 9330)
            ),
            visited
        )
    }

    @Test
    fun `firstMatchInMatrix scans ip-port order and stops at first match`() = runBlocking {
        val visited = mutableListOf<ProbeTarget>()
        val result = useCase.firstMatchInMatrix(
            ipCandidates = listOf("192.168.1.10", "192.168.1.20"),
            portCandidates = listOf(9100, 9330, 9332),
            delayBetweenIpMs = 0L
        ) { target ->
            visited += target
            target.ip == "192.168.1.20" && target.port == 9330
        }

        assertEquals(ProbeTarget("192.168.1.20", 9330), result)
        assertEquals(
            listOf(
                ProbeTarget("192.168.1.10", 9100),
                ProbeTarget("192.168.1.10", 9330),
                ProbeTarget("192.168.1.10", 9332),
                ProbeTarget("192.168.1.20", 9100),
                ProbeTarget("192.168.1.20", 9330)
            ),
            visited
        )
    }

    @Test
    fun `returns null when no candidate matches`() = runBlocking {
        val result = useCase.firstMatchInMatrix(
            ipCandidates = listOf("192.168.1.10"),
            portCandidates = listOf(9100, 9330),
            delayBetweenIpMs = 0L
        ) { false }

        assertNull(result)
    }

    @Test
    fun `handles empty input safely`() = runBlocking {
        val savedResult = useCase.firstMatchFromSavedConnections(emptyList()) { true }
        val matrixResult = useCase.firstMatchInMatrix(
            ipCandidates = emptyList(),
            portCandidates = listOf(9330),
            delayBetweenIpMs = 0L
        ) { true }

        assertNull(savedResult)
        assertNull(matrixResult)
        assertTrue(true)
    }
}
