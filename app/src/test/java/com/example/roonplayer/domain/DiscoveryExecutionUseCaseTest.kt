package com.example.roonplayer.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryExecutionUseCaseTest {

    private val useCase = DiscoveryExecutionUseCase()

    @Test
    fun `execute skips fallback when primary scan already discovers core`() = runBlocking {
        var discovered = 0
        var fallbackCalls = 0
        val delays = mutableListOf<Long>()

        val result = useCase.execute(
            runPrimaryScan = { discovered = 2 },
            runFallbackScan = {
                fallbackCalls++
                discovered += 1
            },
            getDiscoveredCount = { discovered },
            waitAfterPrimaryMs = 100L,
            waitAfterFallbackMs = 200L,
            delayFn = { delays += it }
        )

        assertFalse(result.fallbackTriggered)
        assertTrue(result.hasDiscoveredCore)
        assertEquals(2, result.discoveredCount)
        assertEquals(0, fallbackCalls)
        assertEquals(listOf(100L, 200L), delays)
    }

    @Test
    fun `execute triggers fallback when primary finds nothing`() = runBlocking {
        var discovered = 0
        var fallbackCalls = 0
        val delays = mutableListOf<Long>()

        val result = useCase.execute(
            runPrimaryScan = { /* no discovery */ },
            runFallbackScan = {
                fallbackCalls++
                discovered = 1
            },
            getDiscoveredCount = { discovered },
            waitAfterPrimaryMs = 300L,
            waitAfterFallbackMs = 400L,
            delayFn = { delays += it }
        )

        assertTrue(result.fallbackTriggered)
        assertTrue(result.hasDiscoveredCore)
        assertEquals(1, result.discoveredCount)
        assertEquals(1, fallbackCalls)
        assertEquals(listOf(300L, 400L), delays)
    }

    @Test
    fun `execute returns no discovery when both scans fail`() = runBlocking {
        var discovered = 0

        val result = useCase.execute(
            runPrimaryScan = { /* no-op */ },
            runFallbackScan = { /* no-op */ },
            getDiscoveredCount = { discovered },
            waitAfterPrimaryMs = 10L,
            waitAfterFallbackMs = 20L,
            delayFn = { /* no-op */ }
        )

        assertTrue(result.fallbackTriggered)
        assertFalse(result.hasDiscoveredCore)
        assertEquals(0, result.discoveredCount)
    }

    @Test
    fun `execute clamps negative waits to zero for defensive compatibility`() = runBlocking {
        val delays = mutableListOf<Long>()

        useCase.execute(
            runPrimaryScan = { },
            runFallbackScan = { },
            getDiscoveredCount = { 0 },
            waitAfterPrimaryMs = -1L,
            waitAfterFallbackMs = -5L,
            delayFn = { delays += it }
        )

        assertEquals(listOf(0L, 0L), delays)
    }
}
