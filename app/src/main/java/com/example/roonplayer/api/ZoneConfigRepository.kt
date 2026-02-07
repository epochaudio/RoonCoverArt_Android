package com.example.roonplayer.api

import android.content.SharedPreferences
import org.json.JSONObject

class ZoneConfigRepository(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        const val OUTPUT_ID_KEY = "roon_output_id"
        /**
         * Roon 的 settings(zone 控件)在回显当前选择时，通常需要一个可展示的名称字段（社区示例为 `name`）。
         * 仅持久化 output_id 会导致重启后“有值但显示为空”，用户会误以为没有保存成功。
         *
         * 这里单独存一份 output_name，既便于回填 UI，又避免把整个 settings JSON 以字符串形式硬塞进偏好存储。
         */
        const val OUTPUT_NAME_KEY = "roon_output_name"
        const val ZONE_CONFIG_KEY = "configured_zone"
        private const val LEGACY_OUTPUT_BY_HOST_PREFIX = "roon_zone_id_"
        private const val LEGACY_ZONE_BY_CORE_PREFIX = "configured_zone_"
    }

    fun saveZoneConfiguration(zoneId: String) {
        sharedPreferences.edit()
            .putString(ZONE_CONFIG_KEY, zoneId)
            .apply()
    }

    fun saveOutputId(outputId: String) {
        sharedPreferences.edit()
            .putString(OUTPUT_ID_KEY, outputId)
            .apply()
    }

    fun saveOutputName(outputName: String) {
        sharedPreferences.edit()
            .putString(OUTPUT_NAME_KEY, outputName)
            .apply()
    }

    fun getStoredOutputName(): String? {
        return sharedPreferences.getString(OUTPUT_NAME_KEY, null)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 单 Core 模式下统一读取 output_id。
     * 优先读取主键；若只有历史 host 维度键，则迁移到主键后返回。
     */
    fun getStoredOutputId(): String? {
        val currentOutputId = sharedPreferences.getString(OUTPUT_ID_KEY, null)
        if (!currentOutputId.isNullOrBlank()) {
            return currentOutputId
        }

        val legacyOutputEntry = sharedPreferences.all.entries.firstOrNull { (key, value) ->
            key.startsWith(LEGACY_OUTPUT_BY_HOST_PREFIX) && value is String && value.isNotBlank()
        } ?: return null

        val legacyOutputId = legacyOutputEntry.value as String
        sharedPreferences.edit()
            .putString(OUTPUT_ID_KEY, legacyOutputId)
            .remove(legacyOutputEntry.key)
            .apply()
        return legacyOutputId
    }

    fun loadZoneConfiguration(
        findZoneIdByOutputId: (String) -> String?
    ): String? {
        val existingZone = sharedPreferences.getString(ZONE_CONFIG_KEY, null)
        if (existingZone != null) {
            return existingZone
        }

        val legacyZoneEntry = sharedPreferences.all.entries.firstOrNull { (key, value) ->
            key.startsWith(LEGACY_ZONE_BY_CORE_PREFIX) && value is String && value.isNotBlank()
        }
        val legacyZone = legacyZoneEntry?.value as? String
        if (!legacyZone.isNullOrBlank()) {
            sharedPreferences.edit()
                .putString(ZONE_CONFIG_KEY, legacyZone)
                .remove(legacyZoneEntry.key)
                .apply()
            return legacyZone
        }

        val legacyOutput = getStoredOutputId()
        if (legacyOutput != null) {
            val mappedZoneId = findZoneIdByOutputId(legacyOutput)
            if (mappedZoneId != null) {
                saveZoneConfiguration(mappedZoneId)
                return mappedZoneId
            }
        }

        return null
    }

    fun findZoneIdByOutputId(
        outputId: String,
        zones: Map<String, JSONObject>
    ): String? {
        for ((zoneId, zone) in zones) {
            val outputs = zone.optJSONArray("outputs")
            if (outputs != null) {
                for (i in 0 until outputs.length()) {
                    val output = outputs.getJSONObject(i)
                    if (output.optString("output_id") == outputId) {
                        return zoneId
                    }
                }
            }
        }
        return null
    }
}
