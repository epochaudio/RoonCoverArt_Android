package com.example.roonplayer.domain

data class DiscoveryPolicyConfig(
    val webSocketPort: Int,
    val firstTimeIpSuffixes: List<Int>,
    val reconnectIpSuffixes: List<Int>,
    val portPolicy: DiscoveryPortPolicy,
    val localScanIpSuffixes: List<Int>,
    val commonNetworkPrioritySuffixes: List<Int>,
    val commonNetworkSegments: List<String>,
    val soodNetworkSegments: List<String>,
    val soodTargetSuffixes: List<Int>,
    val historicalCoreIps: List<String>
) {
    companion object {
        fun forRoonDefaults(webSocketPort: Int): DiscoveryPolicyConfig {
            val firstTimeProbePortPool = (9100..9110).toList() +
                listOf(9120, 9130, 9140, 9150, 9160, 9170, 9180, 9190, 9200) +
                (9331..9339).toList()

            return DiscoveryPolicyConfig(
                webSocketPort = webSocketPort,
                firstTimeIpSuffixes = listOf(1, 100, 101, 150, 196, 200),
                reconnectIpSuffixes = listOf(196, 100, 200),
                // 为什么拆成端口策略对象：
                // 发现流程里的端口“用途”不同（首次探测/重连探测/公告兜底），
                // 分层后可以独立调整，不会因为同一个列表被复用而产生策略漂移。
                portPolicy = DiscoveryPortPolicy(
                    firstTimeProbePortPool = firstTimeProbePortPool,
                    reconnectProbePortPool = listOf(9100, 9332, 9001, 9002),
                    announcementFallbackPortPool = listOf(9100, 9332, 9001, 9002)
                ),
                localScanIpSuffixes = listOf(1, 2, 10, 100, 101, 102, 196, 200, 254),
                commonNetworkPrioritySuffixes = listOf(1, 2, 100, 196),
                commonNetworkSegments = listOf("192.168.0", "192.168.1", "10.0.0", "10.1.0"),
                soodNetworkSegments = listOf(
                    "192.168.0", "192.168.1", "192.168.2", "192.168.10", "192.168.11",
                    "10.0.0", "10.0.1", "10.1.0", "172.16.0", "172.16.1"
                ),
                soodTargetSuffixes = listOf(1, 2, 10, 100, 101, 102, 200, 254),
                historicalCoreIps = listOf("192.168.0.196")
            )
        }
    }
}

data class DiscoveryTargets(
    val ipCandidates: List<String>,
    val portCandidates: List<Int>
)

class DiscoveryCandidateUseCase(
    private val config: DiscoveryPolicyConfig
) {
    fun directPortDetectionTargets(
        networkBase: String,
        gateway: String,
        isFirstTime: Boolean
    ): DiscoveryTargets {
        val ipCandidates = if (isFirstTime) {
            // 为什么把 gateway 永远放首位：在家庭网络里网关地址命中率最高，
            // 先探测它能最快给出“可连/不可连”反馈，缩短首次等待时间。
            (listOf(gateway) + buildIpCandidates(networkBase, config.firstTimeIpSuffixes)).distinct()
        } else {
            buildIpCandidates(networkBase, config.reconnectIpSuffixes)
                .distinct()
                .filter { it != gateway }
        }

        val ports = if (isFirstTime) {
            withWebSocketPort(config.portPolicy.firstTimeProbePortPool)
        } else {
            withWebSocketPort(config.portPolicy.reconnectProbePortPool)
        }

        return DiscoveryTargets(
            ipCandidates = ipCandidates,
            portCandidates = ports
        )
    }

    fun knownRangeScanTargets(
        networkBase: String,
        gateway: String
    ): DiscoveryTargets {
        val ipCandidates = mutableListOf<String>()
        ipCandidates.addAll(listOf(gateway) + buildIpCandidates(networkBase, config.localScanIpSuffixes))

        for (network in config.commonNetworkSegments) {
            if (network == networkBase) {
                continue
            }
            ipCandidates.addAll(buildIpCandidates(network, config.commonNetworkPrioritySuffixes))
        }

        return DiscoveryTargets(
            ipCandidates = ipCandidates.distinct(),
            portCandidates = withWebSocketPort(config.portPolicy.reconnectProbePortPool)
        )
    }

    fun announcementProbePorts(primaryPort: Int): List<Int> {
        return (listOf(primaryPort) + withWebSocketPort(config.portPolicy.announcementFallbackPortPool)).distinct()
    }

    fun soodBroadcastAddresses(): List<String> {
        return config.soodNetworkSegments.map { segment -> "$segment.255" }
    }

    fun soodKnownIpCandidates(gateway: String): List<String> {
        val candidates = mutableListOf<String>()
        candidates.addAll(config.historicalCoreIps)
        candidates.add(gateway)
        for (segment in config.soodNetworkSegments) {
            candidates.addAll(buildIpCandidates(segment, config.soodTargetSuffixes))
        }
        return candidates.distinct()
    }

    private fun withWebSocketPort(portCandidates: List<Int>): List<Int> {
        return (listOf(config.webSocketPort) + portCandidates).distinct()
    }

    private fun buildIpCandidates(networkBase: String, suffixes: List<Int>): List<String> {
        return suffixes.map { suffix -> "$networkBase.$suffix" }
    }
}

data class DiscoveryPortPolicy(
    val firstTimeProbePortPool: List<Int>,
    val reconnectProbePortPool: List<Int>,
    val announcementFallbackPortPool: List<Int>
)
