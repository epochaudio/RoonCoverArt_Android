package com.example.roonplayer.network.registration

import org.json.JSONObject

data class PairingRequest(
    val endpoint: String,
    val body: JSONObject
)

class PairingServiceCodec(
    private val serviceName: String = "com.roonlabs.pairing:1"
) {
    fun buildGetPairingRequest(extensionId: String): PairingRequest {
        return PairingRequest(
            endpoint = "$serviceName/get_pairing",
            body = JSONObject().apply {
                put("extension_id", extensionId)
            }
        )
    }

    fun buildPairRequest(extensionId: String): PairingRequest {
        return PairingRequest(
            endpoint = "$serviceName/pair",
            body = JSONObject().apply {
                put("extension_id", extensionId)
            }
        )
    }

    fun buildSubscribePairingRequest(subscriptionKey: String): PairingRequest {
        return PairingRequest(
            endpoint = "$serviceName/subscribe_pairing",
            body = JSONObject().apply {
                put("subscription_key", subscriptionKey)
            }
        )
    }

    fun buildUnsubscribePairingRequest(subscriptionKey: String): PairingRequest {
        return PairingRequest(
            endpoint = "$serviceName/unsubscribe_pairing",
            body = JSONObject().apply {
                put("subscription_key", subscriptionKey)
            }
        )
    }
}
