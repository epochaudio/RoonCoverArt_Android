package com.example.roonplayer.network.moo

import com.example.roonplayer.network.MooMessage

/**
 * Routes parsed MOO frames using request-id first semantics.
 */
class MooRouter(
    private val session: MooSession,
    private val strictUnknownResponseRequestId: Boolean
) {

    fun route(
        message: MooMessage,
        onInboundRequest: (MooMessage) -> Unit,
        onInboundResponse: (MooMessage, PendingMooRequest?) -> Unit,
        onInboundSubscriptionEvent: (MooMessage, PendingMooRequest) -> Unit,
        onProtocolError: (String) -> Unit
    ): Boolean {
        return when (message.verb) {
            VERB_REQUEST -> {
                onInboundRequest(message)
                true
            }
            VERB_RESPONSE -> {
                handleResponseLikeMessage(
                    message = message,
                    completePending = true,
                    onInboundResponse = onInboundResponse,
                    onInboundSubscriptionEvent = onInboundSubscriptionEvent,
                    onProtocolError = onProtocolError
                )
            }
            VERB_CONTINUE -> {
                handleResponseLikeMessage(
                    message = message,
                    completePending = false,
                    onInboundResponse = onInboundResponse,
                    onInboundSubscriptionEvent = onInboundSubscriptionEvent,
                    onProtocolError = onProtocolError
                )
            }
            VERB_COMPLETE -> {
                handleResponseLikeMessage(
                    message = message,
                    completePending = true,
                    onInboundResponse = onInboundResponse,
                    onInboundSubscriptionEvent = onInboundSubscriptionEvent,
                    onProtocolError = onProtocolError
                )
            }
            else -> {
                onProtocolError("Unsupported MOO verb: ${message.verb}")
                false
            }
        }
    }

    private fun handleResponseLikeMessage(
        message: MooMessage,
        completePending: Boolean,
        onInboundResponse: (MooMessage, PendingMooRequest?) -> Unit,
        onInboundSubscriptionEvent: (MooMessage, PendingMooRequest) -> Unit,
        onProtocolError: (String) -> Unit
    ): Boolean {
        val requestId = message.requestId
        if (requestId.isNullOrBlank()) {
            onProtocolError("Missing Request-Id on ${message.verb} ${message.servicePath}")
            return false
        }

        val pending = if (completePending) {
            session.completePending(requestId)
        } else {
            session.peekPending(requestId)
        }

        if (pending == null) {
            onProtocolError(
                "Unknown Request-Id response: request_id=$requestId, verb=${message.verb}, endpoint=${message.servicePath}"
            )
            return !strictUnknownResponseRequestId
        }

        if (pending.category == MooRequestCategory.SUBSCRIPTION && message.verb == VERB_CONTINUE) {
            onInboundSubscriptionEvent(message, pending)
            return true
        }

        onInboundResponse(message, pending)
        return true
    }

    companion object {
        private const val VERB_REQUEST = "REQUEST"
        private const val VERB_RESPONSE = "RESPONSE"
        private const val VERB_CONTINUE = "CONTINUE"
        private const val VERB_COMPLETE = "COMPLETE"
    }
}
