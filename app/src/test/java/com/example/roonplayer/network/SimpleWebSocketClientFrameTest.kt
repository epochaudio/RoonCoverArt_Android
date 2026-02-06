package com.example.roonplayer.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleWebSocketClientFrameTest {

    @Test
    fun `readMooMessage skips ping frame and continues reading payload frame`() {
        val client = newClient()
        val ping = buildFrame(opcode = 0x9, payload = "ping".toByteArray())
        val payload = buildFrame(opcode = 0x2, payload = "MOO".toByteArray())

        val result = invokeReadMooMessage(client, ping + payload)

        assertEquals("MOO", result)
    }

    @Test
    fun `readMooMessage reassembles fragmented binary frames`() {
        val client = newClient()
        val first = buildFrame(opcode = 0x2, payload = "MO".toByteArray(), fin = false)
        val second = buildFrame(opcode = 0x0, payload = "O".toByteArray(), fin = true)

        val result = invokeReadMooMessage(client, first + second)

        assertEquals("MOO", result)
    }

    @Test
    fun `readMooMessage supports masked server frame without misalignment`() {
        val client = newClient()
        val frame = buildFrame(
            opcode = 0x1,
            payload = "hello".toByteArray(),
            fin = true,
            masked = true,
            maskKey = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        )

        val result = invokeReadMooMessage(client, frame)

        assertEquals("hello", result)
    }

    @Test
    fun `readMooMessage returns null on close frame`() {
        val client = newClient()
        val close = buildFrame(opcode = 0x8, payload = ByteArray(0))

        val result = invokeReadMooMessage(client, close)

        assertNull(result)
    }

    private fun invokeReadMooMessage(
        client: SimpleWebSocketClient,
        bytes: ByteArray
    ): String? {
        val method = SimpleWebSocketClient::class.java.getDeclaredMethod(
            "readMooMessage",
            InputStream::class.java
        )
        method.isAccessible = true
        return method.invoke(client, ByteArrayInputStream(bytes)) as String?
    }

    private fun newClient(): SimpleWebSocketClient {
        return SimpleWebSocketClient(
            host = "127.0.0.1",
            port = 9330,
            connectTimeoutMs = 1000,
            handshakeTimeoutMs = 1000,
            readTimeoutMs = 1000,
            onMessage = {}
        )
    }

    private fun buildFrame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        masked: Boolean = false,
        maskKey: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    ): ByteArray {
        val finBit = if (fin) 0x80 else 0x00
        val firstByte = (finBit or opcode).toByte()
        require(payload.size < 126) { "Test helper only supports payload < 126 bytes" }

        if (!masked) {
            val secondByte = payload.size.toByte()
            return byteArrayOf(firstByte, secondByte) + payload
        }

        val secondByte = (0x80 or payload.size).toByte()
        val maskedPayload = ByteArray(payload.size)
        for (i in payload.indices) {
            maskedPayload[i] = (payload[i].toInt() xor maskKey[i % maskKey.size].toInt()).toByte()
        }
        return byteArrayOf(firstByte, secondByte) + maskKey + maskedPayload
    }
}
