package com.example.roonplayer.domain

data class DiscoveryTimingConfig(
    val networkScanIntervalMs: Long,
    val directDetectionWaitMs: Long,
    val announcementSocketTimeoutMs: Int,
    val announcementListenWindowMs: Long,
    val activeSoodSocketTimeoutMs: Int,
    val activeSoodListenWindowMs: Long
) {
    companion object {
        fun defaults(): DiscoveryTimingConfig {
            return DiscoveryTimingConfig(
                networkScanIntervalMs = 100L,
                directDetectionWaitMs = 3000L,
                announcementSocketTimeoutMs = 2000,
                announcementListenWindowMs = 20000L,
                activeSoodSocketTimeoutMs = 8000,
                activeSoodListenWindowMs = 8000L
            )
        }
    }
}
