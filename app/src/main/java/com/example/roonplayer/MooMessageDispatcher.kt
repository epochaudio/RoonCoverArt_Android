package com.example.roonplayer

import org.json.JSONObject

class MooMessageDispatcher(
    private val delegate: Delegate
) {

    interface Delegate {
        fun logDebug(message: String)
        fun logInfo(message: String)
        fun logWarning(message: String)
        fun updateStatus(status: String)
        fun isAuthDialogShown(): Boolean
        fun isConnectionHealthy(): Boolean
        fun sendRegistration()
        fun sendEmptyMooComplete(servicePath: String, requestId: String?)
        fun handleSettingsProtocolMessage(servicePath: String, originalMessage: String, payload: JSONObject?)
        fun handleInfoResponse(jsonBody: JSONObject?)
        fun handleRegistrationResponse(jsonBody: JSONObject?)
        fun handleQueueUpdate(jsonBody: JSONObject)
        fun handleZoneUpdate(jsonBody: JSONObject)
        fun handleNowPlayingChanged(jsonBody: JSONObject)
        fun handleZoneStateChanged(jsonBody: JSONObject)
        fun handleImageResponse(requestId: String?, jsonBody: JSONObject?, originalMessage: String)
        fun hasQueuePayload(body: JSONObject): Boolean
        fun lastRegisterRequestId(): String?
        fun mooCompleteSuccess(): String
    }

    fun dispatch(
        verb: String,
        servicePath: String,
        requestId: String?,
        jsonBody: JSONObject?,
        originalMessage: String
    ) {
        when (verb) {
            "REQUEST" -> handleRequest(servicePath, requestId, jsonBody, originalMessage)
            "RESPONSE" -> handleResponse(servicePath, requestId, jsonBody, originalMessage)
            "CONTINUE" -> handleContinue(servicePath, jsonBody)
            "COMPLETE" -> handleComplete(servicePath, requestId, jsonBody, originalMessage)
        }
    }

    private fun handleRequest(
        servicePath: String,
        requestId: String?,
        jsonBody: JSONObject?,
        originalMessage: String
    ) {
        when {
            isRegistryChangedPath(servicePath) -> {
                delegate.logDebug("Received registry changed event")
                if (delegate.isAuthDialogShown() || !delegate.isConnectionHealthy()) {
                    delegate.logDebug("Registry changed - triggering re-registration check")
                    delegate.updateStatus("Detected Roon settings change. Updating registration...")
                    delegate.sendRegistration()
                }
                delegate.sendEmptyMooComplete(servicePath, requestId)
            }
            isSettingsServicePath(servicePath) -> {
                delegate.handleSettingsProtocolMessage(
                    servicePath = servicePath,
                    originalMessage = originalMessage,
                    payload = jsonBody
                )
            }
            else -> {
                delegate.logDebug("Received generic REQUEST: $servicePath")
                delegate.sendEmptyMooComplete(servicePath, requestId)
            }
        }
    }

    private fun handleResponse(
        servicePath: String,
        requestId: String?,
        jsonBody: JSONObject?,
        originalMessage: String
    ) {
        when {
            isRegistryInfoPath(servicePath) -> {
                delegate.logDebug("Received core info response, proceeding to registration...")
                delegate.handleInfoResponse(jsonBody)
            }
            isRegistryRegisterPath(servicePath) -> {
                delegate.handleRegistrationResponse(jsonBody)
            }
            isTransportSubscribeZonesPath(servicePath) -> {
                delegate.updateStatus("Subscribed to transport service. Waiting for music data...")
            }
            isTransportSubscribeQueuePath(servicePath) -> {
                delegate.logInfo("Queue subscription acknowledged: $servicePath, requestId=$requestId")
                jsonBody?.let { delegate.handleQueueUpdate(it) }
            }
            isImageGetImagePath(servicePath) -> {
                delegate.handleImageResponse(requestId, jsonBody, originalMessage)
            }
            isSettingsServicePath(servicePath) -> {
                delegate.logDebug("Ignore settings RESPONSE message: $servicePath")
            }
        }
    }

    private fun handleContinue(servicePath: String, jsonBody: JSONObject?) {
        when {
            servicePath == "Registered" -> {
                delegate.logDebug("Received registration CONTINUE, processing...")
                delegate.handleRegistrationResponse(jsonBody)
            }
            isSettingsServicePath(servicePath) -> {
                delegate.logDebug("Ignore settings CONTINUE message: $servicePath")
            }
            isTransportSubscribeQueuePath(servicePath) -> {
                jsonBody?.let { delegate.handleQueueUpdate(it) }
            }
            jsonBody?.has("zones") == true -> {
                delegate.handleZoneUpdate(jsonBody)
            }
            else -> {
                jsonBody?.let { body ->
                    when {
                        body.has("zones_changed") -> {
                            delegate.logDebug("Zone event - zones_changed")
                            delegate.handleZoneUpdate(body)
                        }
                        body.has("zones_now_playing_changed") -> {
                            delegate.logDebug("Zone event - zones_now_playing_changed")
                            delegate.handleNowPlayingChanged(body)
                        }
                        body.has("zones_state_changed") -> {
                            delegate.logDebug("Zone event - zones_state_changed")
                            delegate.handleZoneStateChanged(body)
                        }
                        body.has("zones_seek_changed") -> {
                            // Ignore progress updates.
                        }
                        delegate.hasQueuePayload(body) -> {
                            delegate.handleQueueUpdate(body)
                        }
                        else -> {
                            delegate.logDebug("Unknown CONTINUE event: $servicePath")
                        }
                    }
                }
            }
        }
    }

    private fun handleComplete(
        servicePath: String,
        requestId: String?,
        jsonBody: JSONObject?,
        originalMessage: String
    ) {
        val successToken = delegate.mooCompleteSuccess()
        val isRegisterCompleteSuccess =
            servicePath == successToken &&
                requestId != null &&
                requestId == delegate.lastRegisterRequestId()

        when {
            isRegisterCompleteSuccess -> {
                delegate.logDebug("Received registration COMPLETE, processing...")
                delegate.handleRegistrationResponse(jsonBody)
            }
            servicePath == successToken && jsonBody?.has("core_id") == true -> {
                delegate.logDebug("Received core info via COMPLETE, proceeding to registration...")
                delegate.handleInfoResponse(jsonBody)
            }
            servicePath == successToken && originalMessage.contains("Content-Type: image/") -> {
                delegate.logDebug("Received image response via COMPLETE")
                delegate.handleImageResponse(requestId, jsonBody, originalMessage)
            }
            jsonBody?.has("zones") == true -> {
                delegate.handleZoneUpdate(jsonBody)
            }
            isTransportSubscribeQueuePath(servicePath) && jsonBody != null -> {
                delegate.handleQueueUpdate(jsonBody)
            }
            jsonBody != null && delegate.hasQueuePayload(jsonBody) -> {
                delegate.handleQueueUpdate(jsonBody)
            }
            servicePath.contains("NotCompatible") -> {
                delegate.logWarning("Service compatibility issue: $jsonBody")
                val missingServices = jsonBody?.optJSONArray("required_services_missing")
                if (missingServices != null) {
                    val servicesList = (0 until missingServices.length()).map { index ->
                        missingServices.getString(index)
                    }
                    delegate.logWarning("Missing services: $servicesList")
                    val coreServicesMissing = servicesList.filter { !it.contains("settings") }
                    if (coreServicesMissing.isNotEmpty()) {
                        delegate.logInfo("Core services missing: $coreServicesMissing")
                    }
                }
                delegate.updateStatus("Service compatibility issue. Check your Roon version.")
            }
            else -> {
                delegate.logDebug("Received COMPLETE message: $servicePath")
            }
        }
    }

    private fun isRegistryChangedPath(servicePath: String): Boolean =
        servicePath == "com.roonlabs.registry:1/changed"

    private fun isRegistryInfoPath(servicePath: String): Boolean =
        servicePath == "com.roonlabs.registry:1/info"

    private fun isRegistryRegisterPath(servicePath: String): Boolean =
        servicePath == "com.roonlabs.registry:1/register"

    private fun isTransportSubscribeZonesPath(servicePath: String): Boolean =
        servicePath == "com.roonlabs.transport:2/subscribe_zones"

    private fun isTransportSubscribeQueuePath(servicePath: String): Boolean =
        servicePath == "com.roonlabs.transport:2/subscribe_queue"

    private fun isImageGetImagePath(servicePath: String): Boolean =
        servicePath == "com.roonlabs.image:1/get_image"

    private fun isSettingsServicePath(servicePath: String): Boolean =
        servicePath.startsWith("com.roonlabs.settings:1/")
}
