package com.example.roonplayer.domain

data class ZoneSelectionDecision(
    val zoneId: String?,
    val reason: String,
    val persist: Boolean,
    val statusMessage: String? = null
)

/**
 * 领域层只关心“可选择的业务事实”，不关心 JSON/协议字段细节。
 * 这样做是为了让用例可测试、可复用，不被传输层实现绑死。
 */
data class ZoneSnapshot(
    val state: String,
    val hasNowPlaying: Boolean
)

class ZoneSelectionUseCase {

    fun selectZone(
        availableZones: Map<String, ZoneSnapshot>,
        storedZoneId: String?,
        currentZoneId: String?
    ): ZoneSelectionDecision {
        if (availableZones.isEmpty()) {
            return ZoneSelectionDecision(
                zoneId = null,
                reason = "无可用区域",
                persist = false
            )
        }

        if (storedZoneId != null && availableZones.containsKey(storedZoneId)) {
            return ZoneSelectionDecision(
                zoneId = storedZoneId,
                reason = "存储配置",
                persist = false
            )
        }

        if (storedZoneId != null && !availableZones.containsKey(storedZoneId)) {
            val fallbackZoneId = autoSelectZoneId(availableZones)
            return ZoneSelectionDecision(
                zoneId = fallbackZoneId,
                reason = "配置失效回退",
                persist = false,
                statusMessage = "⚠️ 配置的Zone不可用，正在回退到可用区域"
            )
        }

        if (currentZoneId != null && availableZones.containsKey(currentZoneId)) {
            return ZoneSelectionDecision(
                zoneId = currentZoneId,
                reason = "当前选择",
                persist = false
            )
        }

        val autoZoneId = autoSelectZoneId(availableZones)
        return ZoneSelectionDecision(
            zoneId = autoZoneId,
            reason = "自动选择",
            persist = true
        )
    }

    private fun autoSelectZoneId(availableZones: Map<String, ZoneSnapshot>): String? {
        for ((zoneId, zone) in availableZones) {
            if (zone.state == "playing" && zone.hasNowPlaying) {
                return zoneId
            }
        }

        for ((zoneId, zone) in availableZones) {
            if (zone.hasNowPlaying) {
                return zoneId
            }
        }

        return availableZones.keys.firstOrNull()
    }
}
