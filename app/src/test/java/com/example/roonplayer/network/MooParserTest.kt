package com.example.roonplayer.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MooParserTest {

    private val parser = MooParser()

    @Test
    fun `parse returns structured message for valid json response`() {
        val body = JSONObject().apply {
            put("core_id", "core_abc")
        }.toString()
        val message = buildString {
            append("MOO/1 RESPONSE com.roonlabs.registry:1/info\r\n")
            append("Request-Id: 42\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            append("\r\n")
            append(body)
        }

        val parsed = parser.parse(message)

        assertNotNull(parsed)
        assertEquals("RESPONSE", parsed?.verb)
        assertEquals("com.roonlabs.registry:1/info", parsed?.servicePath)
        assertEquals("42", parsed?.requestId)
        assertEquals("application/json", parsed?.contentType)
        assertEquals("core_abc", parsed?.jsonBody?.optString("core_id"))
    }

    @Test
    fun `parse returns null when request id is missing`() {
        val message = buildString {
            append("MOO/1 COMPLETE Success\n")
            append("Content-Type: application/json\n")
            append("Content-Length: 2\n")
            append("\n")
            append("{}")
        }

        assertNull(parser.parse(message))
    }

    @Test
    fun `parse returns null when content length exceeds body length`() {
        val message = buildString {
            append("MOO/1 RESPONSE com.roonlabs.registry:1/info\n")
            append("Request-Id: 1\n")
            append("Content-Type: application/json\n")
            append("Content-Length: 100\n")
            append("\n")
            append("{}")
        }

        assertNull(parser.parse(message))
    }

    @Test
    fun `parse keeps non-json payload without json decode`() {
        val binaryLike = "\\u00FF\\u00D8binary" // simulated jpeg marker text in string form
        val message = buildString {
            append("MOO/1 COMPLETE Success\n")
            append("Request-Id: 7\n")
            append("Content-Type: image/jpeg\n")
            append("Content-Length: ${binaryLike.toByteArray(Charsets.ISO_8859_1).size}\n")
            append("\n")
            append(binaryLike)
        }

        val parsed = parser.parse(message)

        assertNotNull(parsed)
        assertEquals("image/jpeg", parsed?.contentType)
        assertNull(parsed?.jsonBody)
    }

    @Test
    fun `parse returns null for malformed headers`() {
        val message = buildString {
            append("MOO/1 RESPONSE com.roonlabs.registry:1/info\n")
            append("Request-Id 8\n") // malformed header, missing colon
            append("Content-Length: 0\n")
            append("\n")
        }

        assertNull(parser.parse(message))
    }
}
