package com.example.roonplayer.network

data class SoodMessage(
    val version: Int,
    val type: Char,
    val properties: Map<String, String>
)

class SoodProtocolCodec {

    fun buildServiceQuery(serviceId: String): ByteArray {
        val keyBytes = QUERY_SERVICE_ID_KEY.toByteArray(Charsets.UTF_8)
        val valueBytes = serviceId.toByteArray(Charsets.UTF_8)
        val query = ByteArray(4 + 1 + 1 + 1 + keyBytes.size + 1 + valueBytes.size)
        var index = 0

        query[index++] = 'S'.code.toByte()
        query[index++] = 'O'.code.toByte()
        query[index++] = 'O'.code.toByte()
        query[index++] = 'D'.code.toByte()
        query[index++] = DEFAULT_VERSION.toByte()
        query[index++] = QUERY_TYPE.code.toByte()
        query[index++] = keyBytes.size.toByte()
        System.arraycopy(keyBytes, 0, query, index, keyBytes.size)
        index += keyBytes.size
        query[index++] = valueBytes.size.toByte()
        System.arraycopy(valueBytes, 0, query, index, valueBytes.size)

        return query
    }

    fun parseMessage(payload: ByteArray): SoodMessage? {
        if (!hasSoodHeader(payload) || payload.size < MIN_MESSAGE_SIZE) {
            return null
        }

        val version = payload[VERSION_INDEX].toInt() and 0xFF
        val type = (payload[TYPE_INDEX].toInt() and 0xFF).toChar()
        var index = BODY_START_INDEX
        val properties = linkedMapOf<String, String>()

        while (index < payload.size) {
            val keyLength = payload[index].toInt() and 0xFF
            index++
            if (keyLength == 0 || index + keyLength > payload.size) {
                break
            }

            val key = String(payload, index, keyLength, Charsets.UTF_8)
            index += keyLength
            if (index >= payload.size) {
                break
            }

            val valueLength = payload[index].toInt() and 0xFF
            index++
            if (valueLength == 0 || index + valueLength > payload.size) {
                break
            }

            val value = String(payload, index, valueLength, Charsets.UTF_8)
            index += valueLength
            properties[key] = value
        }

        return SoodMessage(
            version = version,
            type = type,
            properties = properties
        )
    }

    fun extractPreferredPort(payload: ByteArray): Int? {
        val message = parseMessage(payload) ?: return null
        val httpPort = propertyValueIgnoreCase(message.properties, HTTP_PORT_KEY)?.toIntOrNull()
        if (httpPort != null) {
            return httpPort
        }

        val port = propertyValueIgnoreCase(message.properties, PORT_KEY)?.toIntOrNull()
        if (port != null) {
            return port
        }

        return propertyValueIgnoreCase(message.properties, WS_PORT_KEY)?.toIntOrNull()
    }

    fun propertyValueIgnoreCase(
        properties: Map<String, String>,
        key: String
    ): String? {
        for ((propertyKey, value) in properties) {
            if (propertyKey.equals(key, ignoreCase = true)) {
                return value
            }
        }
        return null
    }

    private fun hasSoodHeader(payload: ByteArray): Boolean {
        return payload.size >= 4 &&
            payload[0] == 'S'.code.toByte() &&
            payload[1] == 'O'.code.toByte() &&
            payload[2] == 'O'.code.toByte() &&
            payload[3] == 'D'.code.toByte()
    }

    companion object {
        private const val MIN_MESSAGE_SIZE = 6
        private const val VERSION_INDEX = 4
        private const val TYPE_INDEX = 5
        private const val BODY_START_INDEX = 6
        private const val DEFAULT_VERSION = 2
        private const val QUERY_TYPE = 'Q'
        private const val QUERY_SERVICE_ID_KEY = "query_service_id"
        private const val HTTP_PORT_KEY = "http_port"
        private const val PORT_KEY = "port"
        private const val WS_PORT_KEY = "ws_port"
    }
}
