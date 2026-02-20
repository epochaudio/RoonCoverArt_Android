package com.example.roonplayer.network

import org.json.JSONObject

/**
 * Data model representing a parsed MOO protocol message.
 */
data class MooMessage(
    val verb: String,         // REQUEST, RESPONSE, CONTINUE, COMPLETE
    val servicePath: String,  // e.g. "service/method" or status (Success/InvalidRequest)
    val requestId: String?,
    val contentLength: Int,
    val contentType: String?,
    val jsonBody: JSONObject?,
    val headers: Map<String, String>,
    val originalMessage: String // Keep raw message if needed (e.g. for forwarding)
)

/**
 * Pure Kotlin parser for MOO protocol messages.
 * Does not depend on Android APIs except org.json.
 */
class MooParser {

    /**
     * Parses a raw string message into a MooMessage object.
     * Returns null if the message is invalid or cannot be parsed.
     */
    fun parse(message: String): MooMessage? {
        try {
            val headerBoundary = findHeaderBoundary(message) ?: return null
            val headerSection = message.substring(0, headerBoundary.first)
            val bodySection = message.substring(headerBoundary.first + headerBoundary.second)
            val lines = headerSection.split("\r\n", "\n")
            if (lines.isEmpty()) return null

            val firstLine = lines[0].trim()
            if (firstLine.startsWith("HTTP/1.1")) {
                return null
            }

            val firstLineParts = firstLine.split(" ", limit = 3)
            if (firstLineParts.size < 2) return null
            if (firstLineParts[0] != "MOO/1") return null

            val verb = firstLineParts[1]
            val servicePath = firstLineParts.getOrNull(2).orEmpty()
            if (verb !in SUPPORTED_VERBS) return null

            var contentLength = 0
            var requestId: String? = null
            var contentType: String? = null
            val headers = linkedMapOf<String, String>()

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) continue
                val colonIndex = line.indexOf(':')
                if (colonIndex <= 0) {
                    return null
                }

                val headerName = line.substring(0, colonIndex).trim().lowercase()
                val headerValue = line.substring(colonIndex + 1).trim()
                headers[headerName] = headerValue

                when (headerName) {
                    HEADER_CONTENT_LENGTH -> {
                        val parsedLength = headerValue.toIntOrNull() ?: return null
                        if (parsedLength < 0) return null
                        contentLength = parsedLength
                    }
                    HEADER_REQUEST_ID -> {
                        requestId = headerValue.takeIf { it.isNotBlank() }
                    }
                    HEADER_CONTENT_TYPE -> {
                        contentType = headerValue.takeIf { it.isNotBlank() }
                    }
                }
            }

            if (verb in REQUIRE_REQUEST_ID_VERBS && requestId.isNullOrBlank()) {
                return null
            }

            val bodyBytes = bodySection.toByteArray(Charsets.ISO_8859_1)
            if (contentLength > bodyBytes.size) {
                return null
            }

            if (contentLength > 0 && contentType.isNullOrBlank()) {
                return null
            }

            val effectiveBodyBytes = if (contentLength == 0) ByteArray(0) else bodyBytes.copyOf(contentLength)
            var jsonBody: JSONObject? = null
            if (contentLength > 0 && contentType?.contains("application/json", ignoreCase = true) == true) {
                val bodyString = String(effectiveBodyBytes, Charsets.UTF_8)
                if (bodyString.isNotBlank()) {
                    jsonBody = JSONObject(bodyString)
                }
            }

            return MooMessage(
                verb = verb,
                servicePath = servicePath,
                requestId = requestId,
                contentLength = contentLength,
                contentType = contentType,
                jsonBody = jsonBody,
                headers = headers,
                originalMessage = message
            )
        } catch (e: Exception) {
            // Parsing failed
            return null
        }
    }

    private fun findHeaderBoundary(message: String): Pair<Int, Int>? {
        val crlfBoundary = message.indexOf("\r\n\r\n")
        if (crlfBoundary >= 0) {
            return crlfBoundary to 4
        }
        val lfBoundary = message.indexOf("\n\n")
        if (lfBoundary >= 0) {
            return lfBoundary to 2
        }
        return null
    }

    companion object {
        private val SUPPORTED_VERBS = setOf("REQUEST", "RESPONSE", "CONTINUE", "COMPLETE")
        private val REQUIRE_REQUEST_ID_VERBS = setOf("REQUEST", "RESPONSE", "CONTINUE", "COMPLETE")
        private const val HEADER_REQUEST_ID = "request-id"
        private const val HEADER_CONTENT_LENGTH = "content-length"
        private const val HEADER_CONTENT_TYPE = "content-type"
    }
}
