package com.example.roonplayer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoodProtocolCodecTest {

    private val codec = SoodProtocolCodec()

    @Test
    fun `buildServiceQuery creates parseable SOOD query`() {
        val payload = codec.buildServiceQuery("service-123")
        val message = codec.parseMessage(payload)

        assertNotNull(message)
        assertEquals(2, message?.version)
        assertEquals('Q', message?.type)
        assertEquals("service-123", message?.properties?.get("query_service_id"))
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
        val bytes = ArrayList<Byte>()
        bytes.add('S'.code.toByte())
        bytes.add('O'.code.toByte())
        bytes.add('O'.code.toByte())
        bytes.add('D'.code.toByte())
        bytes.add(2)
        bytes.add(type.code.toByte())

        for ((key, value) in properties) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            bytes.add(keyBytes.size.toByte())
            for (b in keyBytes) bytes.add(b)
            bytes.add(valueBytes.size.toByte())
            for (b in valueBytes) bytes.add(b)
        }

        return bytes.toByteArray()
    }
}
