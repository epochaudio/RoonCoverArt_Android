package com.example.roonplayer

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MooMessageDispatcherTest {

    @Test
    fun `response routing matches exact registry info endpoint only`() {
        val delegate = RecordingDelegate()
        val dispatcher = MooMessageDispatcher(delegate)

        dispatcher.dispatch(
            verb = "RESPONSE",
            servicePath = "com.roonlabs.registry:1/info_handler",
            requestId = "1",
            jsonBody = JSONObject(),
            originalMessage = ""
        )

        assertFalse(delegate.infoHandled)

        dispatcher.dispatch(
            verb = "RESPONSE",
            servicePath = "com.roonlabs.registry:1/info",
            requestId = "2",
            jsonBody = JSONObject(),
            originalMessage = ""
        )

        assertTrue(delegate.infoHandled)
    }

    @Test
    fun `request routing matches exact registry changed endpoint only`() {
        val delegate = RecordingDelegate()
        val dispatcher = MooMessageDispatcher(delegate)

        dispatcher.dispatch(
            verb = "REQUEST",
            servicePath = "com.roonlabs.registry:1/changed_extra",
            requestId = "3",
            jsonBody = JSONObject(),
            originalMessage = ""
        )

        assertFalse(delegate.registrationSent)

        dispatcher.dispatch(
            verb = "REQUEST",
            servicePath = "com.roonlabs.registry:1/changed",
            requestId = "4",
            jsonBody = JSONObject(),
            originalMessage = ""
        )

        assertTrue(delegate.registrationSent)
    }

    private class RecordingDelegate : MooMessageDispatcher.Delegate {
        var infoHandled = false
        var registrationSent = false

        override fun logDebug(message: String) = Unit
        override fun logInfo(message: String) = Unit
        override fun logWarning(message: String) = Unit
        override fun updateStatus(status: String) = Unit
        override fun isAuthDialogShown(): Boolean = true
        override fun isConnectionHealthy(): Boolean = false
        override fun sendRegistration() {
            registrationSent = true
        }

        override fun sendEmptyMooComplete(servicePath: String, requestId: String?) = Unit

        override fun handleSettingsProtocolMessage(
            servicePath: String,
            originalMessage: String,
            payload: JSONObject?
        ) = Unit

        override fun handleInfoResponse(jsonBody: JSONObject?) {
            infoHandled = true
        }

        override fun handleRegistrationResponse(jsonBody: JSONObject?) = Unit
        override fun handleQueueUpdate(jsonBody: JSONObject) = Unit
        override fun handleZoneUpdate(jsonBody: JSONObject) = Unit
        override fun handleNowPlayingChanged(jsonBody: JSONObject) = Unit
        override fun handleZoneStateChanged(jsonBody: JSONObject) = Unit
        override fun handleImageResponse(requestId: String?, jsonBody: JSONObject?, originalMessage: String) = Unit
        override fun hasQueuePayload(body: JSONObject): Boolean = false
        override fun lastRegisterRequestId(): String? = null
        override fun mooCompleteSuccess(): String = "Success"
    }
}
