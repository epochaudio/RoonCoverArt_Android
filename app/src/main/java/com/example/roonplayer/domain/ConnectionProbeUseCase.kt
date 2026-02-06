package com.example.roonplayer.domain

import kotlinx.coroutines.delay

data class ProbeTarget(
    val ip: String,
    val port: Int
)

class ConnectionProbeUseCase {

    suspend fun firstMatchFromSavedConnections(
        savedConnections: List<Pair<String, Int>>,
        matches: suspend (ProbeTarget) -> Boolean
    ): ProbeTarget? {
        for ((ip, port) in savedConnections) {
            val target = ProbeTarget(ip = ip, port = port)
            if (matches(target)) {
                return target
            }
        }
        return null
    }

    suspend fun firstMatchInMatrix(
        ipCandidates: List<String>,
        portCandidates: List<Int>,
        delayBetweenIpMs: Long,
        matches: suspend (ProbeTarget) -> Boolean
    ): ProbeTarget? {
        for (ip in ipCandidates) {
            for (port in portCandidates) {
                val target = ProbeTarget(ip = ip, port = port)
                if (matches(target)) {
                    return target
                }
            }

            // 为什么按 IP 维度节流：同一 IP 的端口探测需要尽快完成，
            // 然后再切到下一台主机，能减少局域网探测突发流量。
            if (delayBetweenIpMs > 0L) {
                delay(delayBetweenIpMs)
            }
        }
        return null
    }
}
