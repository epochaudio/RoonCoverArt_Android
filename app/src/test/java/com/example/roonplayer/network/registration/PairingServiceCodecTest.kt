package com.example.roonplayer.network.registration

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingServiceCodecTest {

    private val codec = PairingServiceCodec()

    @Test
    fun `build get pairing request`() {
        val request = codec.buildGetPairingRequest("ext_1")

        assertEquals("com.roonlabs.pairing:1/get_pairing", request.endpoint)
        assertEquals("ext_1", request.body.optString("extension_id"))
    }

    @Test
    fun `build subscribe and unsubscribe pairing requests`() {
        val subscribe = codec.buildSubscribePairingRequest("sub_key")
        val unsubscribe = codec.buildUnsubscribePairingRequest("sub_key")

        assertEquals("com.roonlabs.pairing:1/subscribe_pairing", subscribe.endpoint)
        assertEquals("sub_key", subscribe.body.optString("subscription_key"))

        assertEquals("com.roonlabs.pairing:1/unsubscribe_pairing", unsubscribe.endpoint)
        assertEquals("sub_key", unsubscribe.body.optString("subscription_key"))
    }
}
