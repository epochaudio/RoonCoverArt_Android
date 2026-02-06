package com.example.roonplayer.domain

import kotlinx.coroutines.delay

data class DiscoveryExecutionResult(
    val fallbackTriggered: Boolean,
    val discoveredCount: Int
) {
    val hasDiscoveredCore: Boolean
        get() = discoveredCount > 0
}

class DiscoveryExecutionUseCase {

    suspend fun execute(
        runPrimaryScan: suspend () -> Unit,
        runFallbackScan: suspend () -> Unit,
        getDiscoveredCount: () -> Int,
        waitAfterPrimaryMs: Long,
        waitAfterFallbackMs: Long,
        delayFn: suspend (Long) -> Unit = { delay(it) }
    ): DiscoveryExecutionResult {
        runPrimaryScan()
        delayFn(waitAfterPrimaryMs.coerceAtLeast(0L))

        var fallbackTriggered = false
        if (getDiscoveredCount() == 0) {
            fallbackTriggered = true
            runFallbackScan()
        }

        // 为什么即使主扫描已命中也保留统一等待：
        // 这是对现有行为的兼容约束，避免改动本次重构目标之外的连接时序。
        delayFn(waitAfterFallbackMs.coerceAtLeast(0L))

        return DiscoveryExecutionResult(
            fallbackTriggered = fallbackTriggered,
            discoveredCount = getDiscoveredCount()
        )
    }
}
