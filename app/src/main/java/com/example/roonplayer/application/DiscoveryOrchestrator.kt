package com.example.roonplayer.application

import com.example.roonplayer.domain.DiscoveryExecutionResult
import com.example.roonplayer.domain.DiscoveryExecutionUseCase

data class DiscoveredCoreEndpoint(
    val ip: String,
    val port: Int
)

data class DiscoveryOrchestrationResult(
    val execution: DiscoveryExecutionResult,
    val selectedCore: DiscoveredCoreEndpoint?
)

class DiscoveryOrchestrator(
    private val discoveryExecutionUseCase: DiscoveryExecutionUseCase
) {

    suspend fun runAutomaticDiscovery(
        runPrimaryScan: suspend () -> Unit,
        runFallbackScan: suspend () -> Unit,
        getDiscoveredCores: () -> Collection<DiscoveredCoreEndpoint>,
        waitAfterPrimaryMs: Long,
        waitAfterFallbackMs: Long
    ): DiscoveryOrchestrationResult {
        val execution = discoveryExecutionUseCase.execute(
            runPrimaryScan = runPrimaryScan,
            runFallbackScan = runFallbackScan,
            getDiscoveredCount = { getDiscoveredCores().size },
            waitAfterPrimaryMs = waitAfterPrimaryMs,
            waitAfterFallbackMs = waitAfterFallbackMs
        )

        return DiscoveryOrchestrationResult(
            execution = execution,
            selectedCore = selectCoreForAutoConnect(getDiscoveredCores())
        )
    }

    fun selectCoreForAutoConnect(
        discovered: Collection<DiscoveredCoreEndpoint>
    ): DiscoveredCoreEndpoint? {
        // 为什么由编排层决定“默认连接目标”：
        // 这样 Activity 不需要理解发现顺序细节，只消费最终决策，便于后续替换选择策略。
        return discovered.firstOrNull()
    }
}
