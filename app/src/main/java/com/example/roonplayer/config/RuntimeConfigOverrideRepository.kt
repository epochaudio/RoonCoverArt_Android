package com.example.roonplayer.config

import android.content.SharedPreferences

class RuntimeConfigOverrideRepository(
    private val sharedPreferences: SharedPreferences
) {
    fun loadOverrides(): Map<String, String> {
        val overrides = linkedMapOf<String, String>()
        for ((key, value) in sharedPreferences.all) {
            if (!key.startsWith(OVERRIDE_PREFIX)) {
                continue
            }
            val normalizedKey = key.removePrefix(OVERRIDE_PREFIX)
            if (normalizedKey.isBlank()) {
                continue
            }

            val normalizedValue = normalizeValue(value) ?: continue
            overrides[normalizedKey] = normalizedValue
        }
        return overrides
    }

    private fun normalizeValue(value: Any?): String? {
        return when (value) {
            is String -> value.trim()
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> null
        }
    }

    companion object {
        // 为什么使用统一前缀：
        // 避免与业务状态 key 混淆，让配置覆盖具备可枚举和可清理能力。
        const val OVERRIDE_PREFIX = "runtime_config."
        const val SOURCE_NAME = "shared_preferences"
    }
}
