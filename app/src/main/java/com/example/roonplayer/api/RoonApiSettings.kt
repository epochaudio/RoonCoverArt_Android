package com.example.roonplayer.api

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * RoonApiSettings - 基于官方RoonCoverArt实现的Settings管理类
 * 参考：https://github.com/epochaudio/RoonCoverArt/blob/main/app.js
 */
class RoonApiSettings(
    private val zoneConfigRepository: ZoneConfigRepository,
    private val onZoneConfigChanged: (String?) -> Unit,
    private val getAvailableZones: () -> Map<String, JSONObject>
) {
    companion object {
        private const val TAG = "RoonApiSettings"
        private const val REQUEST_GET_SETTINGS = "get_settings"
        private const val REQUEST_SAVE_SETTINGS = "save_settings"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_VALUES = "values"
        private const val KEY_BODY = "body"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_IS_DRY_RUN = "is_dry_run"
        private const val KEY_IS_DRY_RUN_CAMEL = "isDryRun"
        private const val KEY_OUTPUT = "output"
        private const val KEY_ZONE = "zone"
        private const val KEY_OUTPUT_ID = "output_id"
        /**
         * 社区与官方示例中，zone 控件的值对象通常包含 `name` 用于 UI 回显。
         * 仅返回 output_id 往往会导致“已保存但下次打开设置显示为空”的错觉。
         */
        private const val KEY_NAME = "name"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_HAS_ERROR = "has_error"
        private const val LAYOUT_TYPE_ZONE = "zone"
        private const val OUTPUT_SETTING = KEY_ZONE
        private const val OUTPUT_TITLE = "选择播放区域"

        /**
         * 当我们只有 output_id（例如首次升级到该版本）时，仍然需要给 zone 控件一个可显示的名称，
         * 否则 Roon UI 可能会把当前选择显示成空白，影响可用性与信任感。
         */
        private const val FALLBACK_OUTPUT_NAME = "上次选择"
    }
    
    private var currentSettings = mutableMapOf<String, Any>()
    
    init {
        // 加载保存的设置
        loadSavedSettings()
    }
    
    /**
     * 创建Settings布局 - 按照官方格式
     */
    private fun makeLayout(settings: Map<String, Any>): JSONObject {
        val normalizedValues = normalizeSettingsValues(
            JSONObject().apply {
                for ((key, value) in settings) {
                    put(key, value)
                }
            }
        )
        val settingsValues = JSONObject().apply {
            // zone 控件只读取与其同名 setting 的值；输出单一 source-of-truth 可避免保存时回传旧值。
            normalizedValues.optJSONObject(KEY_ZONE)?.let { zoneValue ->
                // 兼容不同 Roon 客户端/版本对字段名的偏好：
                // 有的 UI 读取 `name`，有的读取 `display_name`。
                // 若只回填其中一个，可能出现“实际已保存，但下次打开设置显示空白”的错觉。
                put(KEY_ZONE, normalizeZoneDisplayFields(JSONObject(zoneValue.toString())))
            }
        }

        // Settings UI 由 Roon Core 渲染并维护可选 Zone 列表。
        // 这里必须始终返回 zone 控件，不能依赖客户端本地 zones 缓存，否则会出现“设置弹窗无可选项”。
        return JSONObject().apply {
            put(KEY_VALUES, settingsValues)
            put(KEY_LAYOUT, JSONArray().apply {
                put(JSONObject().apply {
                    put("type", LAYOUT_TYPE_ZONE)
                    put("title", OUTPUT_TITLE)
                    put("setting", OUTPUT_SETTING)
                })
            })
            put(KEY_HAS_ERROR, false)
        }
    }

    /**
     * zone 控件的值对象至少需要 output_id +（name/display_name 任一）才能稳定回显。
     * 为什么要补齐：
     * - save_settings 回传的对象有时只带 `name`
     * - 我们启动回填时可能只持久化了一个名称字段
     * 这里做“字段镜像”，保证 UI 取哪个字段都能显示一致文本。
     */
    private fun normalizeZoneDisplayFields(zoneValue: JSONObject): JSONObject {
        val name = zoneValue.optString(KEY_NAME).takeIf { it.isNotBlank() }
        val displayName = zoneValue.optString(KEY_DISPLAY_NAME).takeIf { it.isNotBlank() }

        when {
            name != null && displayName == null -> zoneValue.put(KEY_DISPLAY_NAME, name)
            name == null && displayName != null -> zoneValue.put(KEY_NAME, displayName)
        }
        return zoneValue
    }
    
    /**
     * 处理get_settings请求 - 官方API回调格式
     */
    fun getSettings(): JSONObject {
        val layout = makeLayout(currentSettings)
        Log.d(TAG, "Get settings: $layout")
        return layout
    }
    
    /**
     * 处理save_settings请求 - 官方API回调格式
     */
    fun saveSettings(newSettings: JSONObject, isDryRun: Boolean): JSONObject {
        val normalizedSettings = normalizeSettingsValues(newSettings)
        Log.d(TAG, "Save settings - isDryRun: $isDryRun, values: $normalizedSettings")

        val settingsMap = convertJsonToMap(normalizedSettings)
        val layout = makeLayout(settingsMap)
        
        if (!isDryRun) {
            // 非 dry-run 才允许落库，避免 Roon 预检阶段污染本地状态。
            currentSettings = settingsMap
            persistSettings()
            
            // 通知Zone配置变化
            val outputId = extractOutputId(normalizedSettings)
            if (outputId != null) {
                // 找到对应的zone_id并通知
                val zoneId = findZoneIdByOutputId(outputId)
                if (zoneId != null) {
                    Log.d(TAG, "Zone selected: $zoneId (from output: $outputId)")
                    onZoneConfigChanged(zoneId)
                } else {
                    Log.w(TAG, "Output selected but zone not available yet: $outputId")
                }
            } else {
                Log.w(TAG, "save_settings committed without output_id: $normalizedSettings")
            }
        }
        
        // Settings 服务响应体应直接返回 settings layout 对象，外层由调用方包装。
        return layout
    }
    
    /**
     * Settings服务注册信息
     */
    fun getServiceInfo(): JSONObject {
        return JSONObject().apply {
            put("get_settings", true)
            put("save_settings", true)
        }
    }
    
    /**
     * 处理Settings服务消息
     */
    fun handleSettingsMessage(message: JSONObject): JSONObject? {
        return try {
            Log.d(TAG, "=== Settings Message Received ===")
            Log.d(TAG, "Full message: $message")
            
            val request = message.optString("request")
            Log.d(TAG, "Request type: $request")
            
            when (request) {
                REQUEST_GET_SETTINGS -> {
                    Log.d(TAG, "Processing get_settings request")
                    val response = getSettings()
                    Log.d(TAG, "get_settings response: $response")
                    response
                }
                REQUEST_SAVE_SETTINGS -> {
                    Log.d(TAG, "Processing save_settings request")
                    val body = message.optJSONObject(KEY_BODY)
                    val isDryRun = extractDryRun(body)
                    val newSettings = extractSettingsValues(body)
                    Log.d(TAG, "save_settings - dry_run: $isDryRun, settings: $newSettings")
                    val response = saveSettings(newSettings, isDryRun)
                    Log.d(TAG, "save_settings response: $response")
                    response
                }
                else -> {
                    Log.w(TAG, "Unknown settings request: $request")
                    Log.d(TAG, "Available keys in message: ${message.keys().asSequence().toList()}")
                    createErrorResponse("Unknown request type: $request")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling settings message: ${e.message}")
            Log.e(TAG, "Stack trace: ${e.stackTrace.joinToString("\n")}")
            createErrorResponse("Settings processing error: ${e.message}")
        }
    }
    
    /**
     * 创建错误响应
     */
    private fun createErrorResponse(errorMessage: String): JSONObject {
        return JSONObject().apply {
            put("status", "Error")
            put("error", errorMessage)
            put(KEY_SETTINGS, JSONObject().apply {
                put(KEY_VALUES, JSONObject())
                put(KEY_LAYOUT, JSONArray())
                put(KEY_HAS_ERROR, true)
            })
        }
    }

    /**
     * 基于 settings servicePath 解析并处理请求。
     * 兼容不同封装格式（settings/values/body）的 save_settings 载荷，避免协议差异导致设置页失效。
     */
    fun handleSettingsServiceRequest(servicePath: String, payload: JSONObject?): JSONObject {
        val requestType = when {
            servicePath.endsWith("/get_settings") -> REQUEST_GET_SETTINGS
            servicePath.endsWith("/save_settings") -> REQUEST_SAVE_SETTINGS
            else -> payload?.optString("request", REQUEST_GET_SETTINGS) ?: REQUEST_GET_SETTINGS
        }
        return when (requestType) {
            REQUEST_GET_SETTINGS -> getSettings()
            REQUEST_SAVE_SETTINGS -> {
                val isDryRun = extractDryRun(payload)
                val newSettings = extractSettingsValues(payload)
                saveSettings(newSettings, isDryRun)
            }
            else -> {
                Log.w(TAG, "Unsupported settings request type: $requestType, path=$servicePath")
                makeLayout(currentSettings)
            }
        }
    }

    private fun extractDryRun(payload: JSONObject?): Boolean {
        if (payload == null) return false
        extractDryRunFromContainer(payload)?.let { return it }
        payload.optJSONObject(KEY_BODY)?.let { nestedBody ->
            extractDryRunFromContainer(nestedBody)?.let { return it }
        }
        payload.optJSONObject(KEY_SETTINGS)?.let { nestedSettings ->
            extractDryRunFromContainer(nestedSettings)?.let { return it }
        }
        return false
    }

    private fun extractSettingsValues(payload: JSONObject?): JSONObject {
        if (payload == null) return JSONObject()

        extractValuesFromContainer(payload)?.let { return it }

        val nestedBody = payload.optJSONObject(KEY_BODY)
        if (nestedBody != null) {
            extractValuesFromContainer(nestedBody)?.let { return it }
        }

        val nestedSettings = payload.optJSONObject(KEY_SETTINGS)
        if (nestedSettings != null) {
            extractValuesFromContainer(nestedSettings)?.let { return it }
        }

        return JSONObject()
    }

    private fun extractDryRunFromContainer(container: JSONObject): Boolean? {
        return when {
            container.has(KEY_IS_DRY_RUN) -> container.optBoolean(KEY_IS_DRY_RUN)
            container.has(KEY_DRY_RUN) -> container.optBoolean(KEY_DRY_RUN)
            container.has(KEY_IS_DRY_RUN_CAMEL) -> container.optBoolean(KEY_IS_DRY_RUN_CAMEL)
            else -> null
        }
    }

    private fun extractValuesFromContainer(container: JSONObject): JSONObject? {
        container.optJSONObject(KEY_SETTINGS)?.let { settingsObject ->
            settingsObject.optJSONObject(KEY_VALUES)?.let { values ->
                return normalizeSettingsValues(values)
            }
            if (looksLikeSettingsValues(settingsObject)) {
                return normalizeSettingsValues(settingsObject)
            }
        }

        container.optJSONObject(KEY_VALUES)?.let { values ->
            return normalizeSettingsValues(values)
        }

        if (looksLikeSettingsValues(container)) {
            return normalizeSettingsValues(container)
        }

        return null
    }

    private fun looksLikeSettingsValues(candidate: JSONObject): Boolean {
        return candidate.has(KEY_OUTPUT) || candidate.has(KEY_ZONE) || candidate.has(KEY_OUTPUT_ID)
    }

    private fun normalizeSettingsValues(values: JSONObject): JSONObject {
        val normalized = JSONObject(values.toString())

        // zone 是当前布局(setting=zone)的权威值；若与 output 冲突，用 zone 覆盖 output。
        val zoneObject = normalized.optJSONObject(KEY_ZONE)
        val zoneOutputId = zoneObject?.optString(KEY_OUTPUT_ID)?.takeIf { it.isNotBlank() }
        if (zoneOutputId != null) {
            normalized.put(KEY_OUTPUT, JSONObject(zoneObject.toString()))
        }

        if (!normalized.has(KEY_ZONE)) {
            normalized.optJSONObject(KEY_OUTPUT)?.let { outputObject ->
                // 一些客户端以 output 键回传，补齐 zone 键可提升 Roon UI 兼容性。
                normalized.put(KEY_ZONE, JSONObject(outputObject.toString()))
            }
        }

        if (!normalized.has(KEY_OUTPUT)) {
            normalized.optJSONObject(KEY_ZONE)?.let { normalizedZoneObject ->
                // 官方示例常用 "zone"，内部统一成 "output" 方便复用原有存储键。
                normalized.put(KEY_OUTPUT, JSONObject(normalizedZoneObject.toString()))
            }
        }

        if (!normalized.has(KEY_OUTPUT) && !normalized.has(KEY_ZONE)) {
            val directOutputId = normalized.optString(KEY_OUTPUT_ID, "")
            if (directOutputId.isNotBlank()) {
                val outputObject = JSONObject().apply {
                    put(KEY_OUTPUT_ID, directOutputId)
                }
                normalized.put(KEY_OUTPUT, JSONObject(outputObject.toString()))
                normalized.put(KEY_ZONE, JSONObject(outputObject.toString()))
            }
        }

        return normalized
    }

    private fun extractOutputId(settingsValues: JSONObject): String? {
        val outputObject = settingsValues.optJSONObject(KEY_ZONE)
            ?: settingsValues.optJSONObject(KEY_OUTPUT)
        val objectOutputId = outputObject?.optString(KEY_OUTPUT_ID)?.takeIf { it.isNotBlank() }
        if (objectOutputId != null) {
            return objectOutputId
        }
        return settingsValues.optString(KEY_OUTPUT_ID).takeIf { it.isNotBlank() }
    }

    /**
     * 从 settings.values 中提取 output 的展示名称。
     * 为什么需要：Roon 的 zone 控件在回显时依赖 name/display_name，否则 UI 可能显示为空。
     */
    private fun extractOutputName(settingsValues: JSONObject): String? {
        val outputObject = settingsValues.optJSONObject(KEY_ZONE)
            ?: settingsValues.optJSONObject(KEY_OUTPUT)
        val name = outputObject?.optString(KEY_NAME)?.takeIf { it.isNotBlank() }
        if (name != null) return name
        return outputObject?.optString(KEY_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
    }

    /**
     * 在输出名称缺失时，尝试从已订阅的 transport zones 快照中反查 output 的 display_name。
     * 为什么这样做：我们希望“重启后立即打开设置”也能稳定显示当前选择，
     * 即使 save_settings 回包里没有带 name 字段，也尽量从运行期数据推导。
     */
    private fun lookupOutputNameFromZones(outputId: String): String? {
        val zones = getAvailableZones()
        for ((_, zone) in zones) {
            val outputs = zone.optJSONArray("outputs") ?: continue
            for (i in 0 until outputs.length()) {
                val output = outputs.optJSONObject(i) ?: continue
                if (output.optString(KEY_OUTPUT_ID) == outputId) {
                    output.optString(KEY_DISPLAY_NAME).takeIf { it.isNotBlank() }?.let { return it }
                    output.optString(KEY_NAME).takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return null
    }
    
    /**
     * 加载保存的设置
     */
    private fun loadSavedSettings() {
        val savedOutput = zoneConfigRepository.getStoredOutputId()
        val savedOutputName = zoneConfigRepository.getStoredOutputName()
        
        if (savedOutput != null) {
            // 创建output对象
            val outputObj = JSONObject().apply {
                put(KEY_OUTPUT_ID, savedOutput)
                val outputName = savedOutputName ?: FALLBACK_OUTPUT_NAME
                // 同时写入 name/display_name，最大化兼容不同版本/实现对字段名的偏好。
                put(KEY_NAME, outputName)
                put(KEY_DISPLAY_NAME, outputName)
            }
            currentSettings[KEY_OUTPUT] = outputObj
            currentSettings[KEY_ZONE] = JSONObject(outputObj.toString())
            Log.d(TAG, "Loaded saved output: $savedOutput")

            // 统一回写主键，完成 legacy key 向 OUTPUT_ID_KEY 的收敛
            zoneConfigRepository.saveOutputId(savedOutput)
        }
    }
    
    /**
     * 保存设置到SharedPreferences
     */
    private fun persistSettings() {
        val settingsAsJson = JSONObject().apply {
            for ((key, value) in currentSettings) {
                put(key, value)
            }
        }

        // 保存output设置（兼容 output/zone 两种字段）
        val outputId = extractOutputId(settingsAsJson)
        if (outputId != null) {
            // 保存 output 名称用于重启后的 settings 回显（避免 UI 为空）。
            val outputName = extractOutputName(settingsAsJson)
                ?: lookupOutputNameFromZones(outputId)
            if (outputName != null) {
                zoneConfigRepository.saveOutputName(outputName)
            }

            // 找到对应的zone_id
            val zoneId = findZoneIdByOutputId(outputId)
            if (zoneId != null) {
                zoneConfigRepository.saveZoneConfiguration(zoneId)
                Log.d(TAG, "Saved zone configuration: $zoneId")
            }

            // 保存output_id用于Settings界面回填
            zoneConfigRepository.saveOutputId(outputId)
            Log.d(TAG, "Saved output setting: $outputId")
        }
    }
    
    /**
     * 通过output_id查找zone_id
     */
    private fun findZoneIdByOutputId(outputId: String): String? {
        return zoneConfigRepository.findZoneIdByOutputId(outputId, getAvailableZones())
    }
    
    /**
     * 将JSONObject转换为Map
     */
    private fun convertJsonToMap(json: JSONObject): MutableMap<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            map[key] = value
        }
        return map
    }
    
    /**
     * 加载Zone配置
     */
    fun loadZoneConfiguration(): String? {
        val zoneId = zoneConfigRepository.loadZoneConfiguration(
            findZoneIdByOutputId = ::findZoneIdByOutputId
        )

        Log.d(TAG, "Loaded zone configuration: zoneId=$zoneId")
        return zoneId
    }
    
    /**
     * 获取当前设置
     */
    fun getCurrentSettings(): Map<String, Any> {
        return currentSettings.toMap()
    }
}
