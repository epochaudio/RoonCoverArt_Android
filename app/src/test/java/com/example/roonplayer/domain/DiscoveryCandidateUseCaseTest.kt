package com.example.roonplayer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryCandidateUseCaseTest {

    private val useCase = DiscoveryCandidateUseCase(
        DiscoveryPolicyConfig.forRoonDefaults(webSocketPort = 9330)
    )

    @Test
    fun `directPortDetectionTargets includes gateway and websocket port on first time`() {
        val targets = useCase.directPortDetectionTargets(
            networkBase = "192.168.1",
            gateway = "192.168.1.1",
            isFirstTime = true
        )

        assertTrue(targets.ipCandidates.first() == "192.168.1.1")
        assertTrue(targets.ipCandidates.contains("192.168.1.196"))
        assertTrue(targets.portCandidates.contains(9330))
        assertTrue(targets.portCandidates.contains(9332))
    }

    @Test
    fun `directPortDetectionTargets excludes gateway on reconnect plan`() {
        val targets = useCase.directPortDetectionTargets(
            networkBase = "192.168.1",
            gateway = "192.168.1.1",
            isFirstTime = false
        )

        assertFalse(targets.ipCandidates.contains("192.168.1.1"))
        assertTrue(targets.ipCandidates.contains("192.168.1.196"))
        assertEquals(listOf(9330, 9100, 9332, 9001, 9002), targets.portCandidates)
    }

    @Test
    fun `knownRangeScanTargets merges local and common segments`() {
        val targets = useCase.knownRangeScanTargets(
            networkBase = "192.168.1",
            gateway = "192.168.1.1"
        )

        assertTrue(targets.ipCandidates.contains("192.168.1.254"))
        assertTrue(targets.ipCandidates.contains("192.168.0.196"))
        assertTrue(targets.ipCandidates.contains("10.0.0.100"))
        assertTrue(targets.portCandidates.contains(9330))
    }

    @Test
    fun `announcementProbePorts keeps primary port first and deduplicated`() {
        val ports = useCase.announcementProbePorts(primaryPort = 9332)

        assertEquals(9332, ports.first())
        assertTrue(ports.contains(9330))
        assertEquals(5, ports.size)
    }

    @Test
    fun `sood candidates include gateway historical and generated segments`() {
        val broadcasts = useCase.soodBroadcastAddresses()
        val candidates = useCase.soodKnownIpCandidates(gateway = "192.168.1.1")

        assertTrue(broadcasts.contains("192.168.0.255"))
        assertTrue(broadcasts.contains("172.16.1.255"))
        assertTrue(candidates.contains("192.168.0.196"))
        assertTrue(candidates.contains("192.168.1.1"))
        assertTrue(candidates.contains("10.0.1.254"))
    }
}
