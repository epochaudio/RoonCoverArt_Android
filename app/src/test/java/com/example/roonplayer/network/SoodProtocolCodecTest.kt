package com.example.roonplayer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoodProtocolCodecTest {

    private val codec = SoodProtocolCodec()

    @Test
    fun `buildServiceQuery creates parseable SOOD query with transaction id`() {
        val payload = codec.buildServiceQuery("service-123")
        val message = codec.parseMessage(payload)

        assertNotNull(message)
        assertEquals(2, message?.version)
        assertEquals('Q', message?.type)
        assertEquals("service-123", message?.properties?.get("query_service_id"))
        assertTrue(message?.properties?.get("_tid").isNullOrBlank().not())
    }

    @Test
    fun `buildServiceQuery includes reply address and reply port when provided`() {
        val payload = codec.buildServiceQuery(
            serviceId = "service-123",
            transactionId = "tid-abc",
            replyAddress = "192.168.1.7",
            replyPort = 45678
        )
        val message = codec.parseMessage(payload)

        assertEquals("tid-abc", message?.properties?.get("_tid"))
        assertEquals("192.168.1.7", message?.properties?.get("_replyaddr"))
        assertEquals("45678", message?.properties?.get("_replyport"))
    }

    @Test
    fun `parseMessage supports official two-byte value length`() {
        val longValue = "x".repeat(300)
        val payload = codec.buildMessage(
            type = 'R',
            properties = linkedMapOf(
                "service_id" to "service-123",
                "display_name" to longValue
            )
        )
        val parsed = codec.parseMessage(payload)

        assertEquals("service-123", parsed?.properties?.get("service_id"))
        assertEquals(longValue, parsed?.properties?.get("display_name"))
    }

    @Test
    fun `parseMessage returns null for non sood payload`() {
        val invalid = "not_sood".toByteArray(Charsets.UTF_8)
        assertNull(codec.parseMessage(invalid))
    }

    @Test
    fun `extractPreferredPort prioritizes http_port then port then ws_port`() {
        val payload = buildSoodMessage(
            type = 'R',
            properties = linkedMapOf(
                "ws_port" to "9002",
                "port" to "9001",
                "http_port" to "9330"
            )
        )

        val port = codec.extractPreferredPort(payload)
        assertEquals(9330, port)
    }

    @Test
    fun `propertyValueIgnoreCase supports mixed-case keys`() {
        val message = buildSoodMessage(
            type = 'R',
            properties = linkedMapOf(
                "Service_Id" to "abc",
                "HTTP_PORT" to "9330"
            )
        )
        val parsed = codec.parseMessage(message)
        assertNotNull(parsed)

        val value = codec.propertyValueIgnoreCase(parsed!!.properties, "service_id")
        assertEquals("abc", value)
    }

    @Test
    fun `extractPreferredPort returns null when no numeric value exists`() {
        val payload = buildSoodMessage(
            type = 'R',
            properties = linkedMapOf(
                "port" to "not_number"
            )
        )

        assertNull(codec.extractPreferredPort(payload))
    }

    private fun buildSoodMessage(
        type: Char,
        properties: Map<String, String>
    ): ByteArray {
        return codec.buildMessage(
            type = type,
            properties = properties
        )
    }
}
