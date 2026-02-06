package com.example.roonplayer.application

import com.example.roonplayer.domain.DiscoveryExecutionUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryOrchestratorTest {

    private val orchestrator = DiscoveryOrchestrator(DiscoveryExecutionUseCase())

    @Test
    fun `runAutomaticDiscovery triggers fallback and returns selected core`() = runBlocking {
        val discovered = mutableListOf<DiscoveredCoreEndpoint>()
        var primaryCalled = false
        var fallbackCalled = false

        val result = orchestrator.runAutomaticDiscovery(
            runPrimaryScan = {
                primaryCalled = true
            },
            runFallbackScan = {
                fallbackCalled = true
                discovered += DiscoveredCoreEndpoint(ip = "192.168.1.10", port = 9330)
            },
            getDiscoveredCores = { discovered },
            waitAfterPrimaryMs = 0L,
            waitAfterFallbackMs = 0L
        )

        assertTrue(primaryCalled)
        assertTrue(fallbackCalled)
        assertTrue(result.execution.fallbackTriggered)
        assertEquals(1, result.execution.discoveredCount)
        assertNotNull(result.selectedCore)
        assertEquals("192.168.1.10", result.selectedCore?.ip)
        assertEquals(9330, result.selectedCore?.port)
    }

    @Test
    fun `runAutomaticDiscovery skips fallback when primary already found core`() = runBlocking {
        val discovered = mutableListOf(
            DiscoveredCoreEndpoint(ip = "192.168.1.20", port = 9330)
        )
        var fallbackCalled = false

        val result = orchestrator.runAutomaticDiscovery(
            runPrimaryScan = {
                // already discovered before fallback check
            },
            runFallbackScan = {
                fallbackCalled = true
            },
            getDiscoveredCores = { discovered },
            waitAfterPrimaryMs = 0L,
            waitAfterFallbackMs = 0L
        )

        assertFalse(fallbackCalled)
        assertFalse(result.execution.fallbackTriggered)
        assertEquals(1, result.execution.discoveredCount)
        assertEquals("192.168.1.20", result.selectedCore?.ip)
        assertEquals(9330, result.selectedCore?.port)
    }

    @Test
    fun `selectCoreForAutoConnect returns null when discovery list is empty`() {
        val selected = orchestrator.selectCoreForAutoConnect(emptyList())
        assertNull(selected)
    }
}
