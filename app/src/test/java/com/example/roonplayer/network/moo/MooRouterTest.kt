package com.example.roonplayer.network.moo

import com.example.roonplayer.network.MooMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MooRouterTest {

    @Test
    fun `route request delegates to inbound request handler`() = runBlocking {
        val session = MooSession(this)
        val router = MooRouter(session = session, strictUnknownResponseRequestId = false)
        var requestHandled = false

        val message = MooMessage(
            verb = "REQUEST",
            servicePath = "com.roonlabs.settings:1/get_settings",
            requestId = "9",
            contentLength = 0,
            contentType = "application/json",
            jsonBody = null,
            headers = emptyMap(),
            originalMessage = ""
        )

        val routed = router.route(
            message = message,
            onInboundRequest = { requestHandled = true },
            onInboundResponse = { _, _ -> },
            onInboundSubscriptionEvent = { _, _ -> },
            onProtocolError = {}
        )

        assertTrue(routed)
        assertTrue(requestHandled)
    }

    @Test
    fun `route continue for subscription keeps pending and emits subscription callback`() = runBlocking {
        val session = MooSession(this)
        session.registerPending(
            requestId = "11",
            endpoint = "com.roonlabs.transport:2/subscribe_zones",
            category = MooRequestCategory.SUBSCRIPTION,
            timeoutMs = 5_000L,
            onTimeout = {}
        )

        val router = MooRouter(session = session, strictUnknownResponseRequestId = false)
        var subscriptionEvents = 0

        val message = MooMessage(
            verb = "CONTINUE",
            servicePath = "com.roonlabs.transport:2/subscribe_zones",
            requestId = "11",
            contentLength = 0,
            contentType = "application/json",
            jsonBody = null,
            headers = emptyMap(),
            originalMessage = ""
        )

        val routed = router.route(
            message = message,
            onInboundRequest = { },
            onInboundResponse = { _, _ -> },
            onInboundSubscriptionEvent = { _, _ -> subscriptionEvents += 1 },
            onProtocolError = {}
        )

        assertTrue(routed)
        assertEquals(1, subscriptionEvents)
        assertTrue(session.peekPending("11") != null)
    }

    @Test
    fun `strict mode rejects unknown request id response`() = runBlocking {
        val session = MooSession(this)
        val router = MooRouter(session = session, strictUnknownResponseRequestId = true)
        var protocolErrors = 0

        val message = MooMessage(
            verb = "RESPONSE",
            servicePath = "com.roonlabs.registry:1/info",
            requestId = "404",
            contentLength = 0,
            contentType = "application/json",
            jsonBody = null,
            headers = emptyMap(),
            originalMessage = ""
        )

        val routed = router.route(
            message = message,
            onInboundRequest = { },
            onInboundResponse = { _, _ -> },
            onInboundSubscriptionEvent = { _, _ -> },
            onProtocolError = { protocolErrors += 1 }
        )

        assertFalse(routed)
        assertEquals(1, protocolErrors)
    }
}
