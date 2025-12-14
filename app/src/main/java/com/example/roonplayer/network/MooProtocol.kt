package com.example.roonplayer.network

import org.json.JSONObject

/**
 * Data model representing a parsed MOO protocol message.
 */
data class MooMessage(
    val verb: String,         // RESPONSE, CONTINUE, COMPLETE
    val servicePath: String,  // e.g. "service/method"
    val requestId: String?,
    val contentLength: Int,
    val jsonBody: JSONObject?,
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
            // Handle both \r\n and \n line endings
            val lines = message.split("\r\n", "\n")
            if (lines.isEmpty()) return null
            
            val firstLine = lines[0]
            
            // Skip HTTP messages (Handled by caller before parsing as MOO, or we can handle here if we want)
            // But for pure MOO parsing, we expect MOO format.
            // MainActivity currently checks for HTTP 101/404 before parsing. 
            // We will assume that check happens before calling this, or we handle it gracefully.
            if (firstLine.startsWith("HTTP/1.1")) {
                return null 
            }
            
            // Parse first line: "MOO/1 RESPONSE service/method" or "MOO/1 COMPLETE"
            val parts = firstLine.split(" ", limit = 3)
            if (parts.size < 2) return null
            
            // parts[0] is "MOO/1", we can optionally validate it
            
            val verb = parts[1] // RESPONSE, COMPLETE, etc.
            val servicePath = if (parts.size > 2) parts[2] else ""
            
            // Parse headers
            var contentLength = 0
            var requestId: String? = null
            var headerEndIndex = 1
            
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) {
                    headerEndIndex = i + 1
                    break
                }
                
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val headerName = line.substring(0, colonIndex).trim()
                    val headerValue = line.substring(colonIndex + 1).trim()
                    
                    when (headerName.lowercase()) {
                        "content-length" -> contentLength = headerValue.toIntOrNull() ?: 0
                        "request-id" -> requestId = headerValue
                    }
                }
            }
            
            // Parse JSON body if present
            var jsonBody: JSONObject? = null
            if (contentLength > 0 && headerEndIndex < lines.size) {
                // Join remaining lines to form the body string
                // Note: subList(from, to) where 'to' is exclusive. 
                // But lines.size is the count, so it covers up to valid index.
                val bodyLines = lines.subList(headerEndIndex, lines.size)
                
                // We need to rejoin with original separators or best guess.
                // Since we split by regex for \r\n or \n, the original separators are lost in the list strings.
                // However, JSON parsing usually tolerates whitespace.
                // A safer way is to find the index in original string, but for now joinToString("\n") works 
                // because we are just parsing JSON.
                val bodyString = bodyLines.joinToString("\n")
                
                if (bodyString.isNotEmpty()) {
                    try {
                        jsonBody = JSONObject(bodyString)
                    } catch (e: Exception) {
                        // Failed to parse JSON body, but we still return the message with null body
                        // or should we fail? MainActivity logged error but proceeded.
                        // We'll proceed with null details.
                    }
                }
            }
            
            return MooMessage(
                verb = verb,
                servicePath = servicePath,
                requestId = requestId,
                contentLength = contentLength,
                jsonBody = jsonBody,
                originalMessage = message
            )
            
        } catch (e: Exception) {
            // Parsing failed
            return null
        }
    }
}
