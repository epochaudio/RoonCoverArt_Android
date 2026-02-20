package com.example.roonplayer.network

import java.util.UUID

data class SoodMessage(
    val version: Int,
    val type: Char,
    val properties: Map<String, String>
)

class SoodProtocolCodec {

    fun buildServiceQuery(
        serviceId: String,
        transactionId: String = generateTransactionId(),
        replyAddress: String? = null,
        replyPort: Int? = null
    ): ByteArray {
        val properties = linkedMapOf(
            QUERY_SERVICE_ID_KEY to serviceId,
            TRANSACTION_ID_KEY to transactionId
        )
        if (!replyAddress.isNullOrBlank()) {
            properties[REPLY_ADDRESS_KEY] = replyAddress
        }
        if (replyPort != null && replyPort > 0) {
            properties[REPLY_PORT_KEY] = replyPort.toString()
        }
        return buildMessage(type = QUERY_TYPE, properties = properties)
    }

    fun buildMessage(
        type: Char,
        properties: Map<String, String>,
        version: Int = DEFAULT_VERSION
    ): ByteArray {
        val output = ArrayList<Byte>()
        output += SOOD_HEADER_BYTES.toList()
        output += version.toByte()
        output += type.code.toByte()

        for ((key, value) in properties) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val valueBytes = value.toByteArray(Charsets.UTF_8)

            require(keyBytes.size in 1..MAX_KEY_LENGTH) {
                "SOOD key length out of bounds: ${keyBytes.size} for key=$key"
            }
            require(valueBytes.size <= MAX_VALUE_LENGTH) {
                "SOOD value length out of bounds: ${valueBytes.size} for key=$key"
            }

            output += keyBytes.size.toByte()
            output.addAll(keyBytes.toList())
            output += ((valueBytes.size shr 8) and BYTE_MASK).toByte()
            output += (valueBytes.size and BYTE_MASK).toByte()
            output.addAll(valueBytes.toList())
        }

        return output.toByteArray()
    }

    fun parseMessage(payload: ByteArray): SoodMessage? {
        if (!hasSoodHeader(payload) || payload.size < MIN_MESSAGE_SIZE) {
            return null
        }

        val version = payload[VERSION_INDEX].toInt() and 0xFF
        val type = (payload[TYPE_INDEX].toInt() and 0xFF).toChar()
        val properties = parsePropertiesWithTwoByteValueLength(payload)
            ?: parsePropertiesWithLegacyOneByteValueLength(payload)
            ?: emptyMap()

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

    private fun parsePropertiesWithTwoByteValueLength(
        payload: ByteArray
    ): Map<String, String>? {
        val properties = linkedMapOf<String, String>()
        var index = BODY_START_INDEX
        while (index < payload.size) {
            if (index + 1 > payload.size) return null
            val keyLength = payload[index].toInt() and BYTE_MASK
            index += 1
            if (keyLength == 0 || index + keyLength > payload.size) return null

            val key = String(payload, index, keyLength, Charsets.UTF_8)
            index += keyLength
            if (index + 2 > payload.size) return null

            val valueLength = ((payload[index].toInt() and BYTE_MASK) shl 8) or
                (payload[index + 1].toInt() and BYTE_MASK)
            index += 2
            if (index + valueLength > payload.size) return null

            val value = String(payload, index, valueLength, Charsets.UTF_8)
            index += valueLength
            properties[key] = value
        }
        return properties
    }

    private fun parsePropertiesWithLegacyOneByteValueLength(
        payload: ByteArray
    ): Map<String, String>? {
        val properties = linkedMapOf<String, String>()
        var index = BODY_START_INDEX
        while (index < payload.size) {
            if (index + 1 > payload.size) return null
            val keyLength = payload[index].toInt() and BYTE_MASK
            index += 1
            if (keyLength == 0 || index + keyLength > payload.size) return null

            val key = String(payload, index, keyLength, Charsets.UTF_8)
            index += keyLength
            if (index + 1 > payload.size) return null

            val valueLength = payload[index].toInt() and BYTE_MASK
            index += 1
            if (index + valueLength > payload.size) return null

            val value = String(payload, index, valueLength, Charsets.UTF_8)
            index += valueLength
            properties[key] = value
        }
        return properties
    }

    private fun generateTransactionId(): String {
        return UUID.randomUUID().toString().replace("-", "")
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
        private const val BYTE_MASK = 0xFF
        private const val MAX_KEY_LENGTH = 255
        private const val MAX_VALUE_LENGTH = 65535
        private const val QUERY_SERVICE_ID_KEY = "query_service_id"
        private const val TRANSACTION_ID_KEY = "_tid"
        private const val REPLY_ADDRESS_KEY = "_replyaddr"
        private const val REPLY_PORT_KEY = "_replyport"
        private const val HTTP_PORT_KEY = "http_port"
        private const val PORT_KEY = "port"
        private const val WS_PORT_KEY = "ws_port"
        private val SOOD_HEADER_BYTES = byteArrayOf(
            'S'.code.toByte(),
            'O'.code.toByte(),
            'O'.code.toByte(),
            'D'.code.toByte()
        )
    }
}
