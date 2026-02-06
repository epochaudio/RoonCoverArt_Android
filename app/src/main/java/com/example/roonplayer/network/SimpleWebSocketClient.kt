package com.example.roonplayer.network

import com.example.roonplayer.MainActivity
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

// WebSocket客户端实现 - 使用Roon的官方WebSocket API
class SimpleWebSocketClient(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int,
    private val handshakeTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val onMessage: (String) -> Unit
) {
    private var socket: Socket? = null
    private var connected = false
    
    companion object {
        private const val DEBUG_ENABLED = true
        private const val FRAME_VERBOSE_LOG = false
        private const val LOG_TAG = "RoonPlayer"
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val PAYLOAD_EXTENDED_16 = 126
        private const val PAYLOAD_EXTENDED_64 = 127
        private const val FIN_BIT = 0x80
        private const val MASK_BIT = 0x80
    }

    private inline fun safeAndroidLog(logCall: () -> Unit) {
        try {
            logCall()
        } catch (_: RuntimeException) {
            // JVM unit tests load a mock android.jar where android.util.Log throws.
            // Logging must never影响协议处理路径，否则会把可恢复事件误判为连接失败。
        }
    }
    
    // Logging methods for SimpleWebSocketClient
    private fun logDebug(message: String) {
        if (DEBUG_ENABLED) safeAndroidLog { android.util.Log.d(LOG_TAG, message) }
    }

    private fun logFrameVerbose(message: String) {
        if (DEBUG_ENABLED && FRAME_VERBOSE_LOG) safeAndroidLog { android.util.Log.d(LOG_TAG, message) }
    }
    
    private fun logInfo(message: String) {
        if (DEBUG_ENABLED) safeAndroidLog { android.util.Log.i(LOG_TAG, message) }
    }
    
    private fun logWarning(message: String) {
        if (DEBUG_ENABLED) safeAndroidLog { android.util.Log.w(LOG_TAG, message) }
    }
    
    private fun logError(message: String, e: Exception? = null) {
        if (DEBUG_ENABLED) safeAndroidLog { android.util.Log.e(LOG_TAG, message, e) }
    }

    private fun logLifecycle(event: String, details: String = "") {
        if (details.isBlank()) {
            logInfo("[WS][$event]")
        } else {
            logInfo("[WS][$event] $details")
        }
    }
    
    fun isConnected(): Boolean = connected
    
    fun getHost(): String = host
    fun getPort(): Int = port
    
    // Use a custom CoroutineScope for managing background tasks
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Throws(Exception::class)
    fun connect() {
        logLifecycle("CONNECT_START", "$host:$port")
        try {
            // Ensure previous connection is cleaned up
            disconnect()
            
            // Reset scope
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            val newSocket = Socket()
            socket = newSocket
            logDebug("Socket object created")
            
            try {
                newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                logDebug("Socket connected successfully")
            } catch (e: Exception) {
                try { newSocket.close() } catch (ignore: Exception) {}
                socket = null
                throw e
            }
            
            socket?.let { sock ->
                logDebug("Connected via TCP, using WebSocket protocol")
                
                // Configure socket for reliable WebSocket communication
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.soTimeout = handshakeTimeoutMs
                
                // 发送WebSocket握手 - 使用标准Roon API路径
                val websocketKey = "dGhlIHNhbXBsZSBub25jZQ==" // Standard sample nonce
                val handshake = buildString {
                    append("GET ${MainActivity.ROON_WS_PATH} HTTP/1.1\r\n")
                    append("Host: $host\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $websocketKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("User-Agent: RoonPlayerAndroid/1.0\r\n")
                    append("\r\n")
                }
                
                logDebug("Sending WebSocket handshake...")
                sock.getOutputStream().write(handshake.toByteArray())
                sock.getOutputStream().flush()
                
                // 读取握手响应
                val input = sock.getInputStream()
                val headerBuffer = ByteArrayOutputStream()
                val tempBuffer = ByteArray(1)
                
                // Read until \r\n\r\n
                var lastFour = 0
                while (true) {
                    val read = input.read(tempBuffer)
                    if (read == -1) throw Exception("EOF during handshake")
                    val b = tempBuffer[0].toInt()
                    headerBuffer.write(b)
                    
                    lastFour = ((lastFour shl 8) or (b and 0xFF))
                    if ((lastFour and 0xFFFFFFFF.toInt()) == 0x0D0A0D0A) { // \r\n\r\n
                        break
                    }
                    // Safety check for header size
                    if (headerBuffer.size() > 4096) throw Exception("Handshake header too large")
                }
                
                val response = headerBuffer.toString()
                if (response.contains("101 Switching Protocols")) {
                    logLifecycle("CONNECT_OK", "$host:$port")
                    connected = true
                    
                    // Reset timeout for normal operation
                    sock.soTimeout = 0 
                } else {
                    throw Exception("WebSocket handshake failed: $response")
                }
                
                logDebug("Socket configured: tcpNoDelay=${sock.tcpNoDelay}, keepAlive=${sock.keepAlive}")
                
                // 开始监听MOO消息
                scope.launch {
                    try {
                        logLifecycle("LOOP_START")
                        
                        // Give a short delay to let the connection stabilize
                        delay(10)
                        
                        while (isActive && connected && !sock.isClosed && sock.isConnected) {
                            try {
                                // Set timeout for each read operation
                                sock.soTimeout = readTimeoutMs
                                
                                val message = readMooMessage(input)
                                if (message != null) {
                                    // Ignore HTTP response leftovers if any (handled in handshake)
                                    if (message.startsWith("HTTP/1.1")) {
                                        logDebug("Ignored duplicate handshake response")
                                        continue
                                    }
                                    
                                    onMessage(message)
                                } else {
                                    logWarning("[WS][REMOTE_EOF] connection closed by remote peer")
                                    break
                                }
                            } catch (e: java.net.SocketTimeoutException) {
                                // This is normal for read timeout if no data sent
                                continue
                            } catch (e: java.io.IOException) {
                                if (connected) {
                                    logError("[WS][LOOP_IO_ERROR] ${e.message}")
                                    break
                                }
                            } catch (e: Exception) {
                                if (connected && e !is CancellationException) {
                                    logError("[WS][LOOP_UNEXPECTED_ERROR] ${e.message}", e)
                                }
                                break
                            }
                        }
                        logLifecycle("LOOP_END")
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            logError("[WS][LOOP_FAILED] ${e.message}", e)
                        }
                    } finally {
                        if (connected) {
                            connected = false
                            try { sock.close() } catch (ignore: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logError("[WS][CONNECT_FAIL] ${e.message}", e)
            connected = false
            throw e
        }
    }
    
    fun send(message: String) {
        socket?.let { sock ->
            try {
                logFrameVerbose("Sending raw TCP message: $message")
                val messageBytes = message.toByteArray(Charsets.UTF_8)
                logFrameVerbose("Message bytes (${messageBytes.size}): ${messageBytes.joinToString(" ") { "%02x".format(it) }}")
                
                val outputStream = sock.getOutputStream()
                outputStream.write(messageBytes)
                outputStream.flush()
                
                logDebug("TCP message sent successfully (${messageBytes.size} bytes)")
                
                // Verify socket is still connected
                if (!sock.isConnected || sock.isClosed) {
                    logWarning("Warning: Socket state after send - connected: ${sock.isConnected}, closed: ${sock.isClosed}")
                }
            } catch (e: Exception) {
                logError("Send failed: ${e.message}", e)
                connected = false
            }
        } ?: logError("Cannot send message: socket is null")
    }
    
    fun sendWebSocketFrame(message: String) {
        socket?.let { sock ->
            try {
                logFrameVerbose("Sending WebSocket frame: $message")
                val messageBytes = message.toByteArray(Charsets.UTF_8)
                
                // Roon 侧消息按二进制帧更稳定，保留既有行为。
                val frame = createWebSocketFrame(messageBytes, OPCODE_BINARY)
                
                val outputStream = sock.getOutputStream()
                outputStream.write(frame)
                outputStream.flush()
                
                logDebug("WebSocket frame sent successfully (${frame.size} bytes)")
            } catch (e: Exception) {
                logError("WebSocket send failed: ${e.message}", e)
                connected = false
            }
        } ?: logError("Cannot send WebSocket frame: socket is null")
    }

    private fun sendPongFrame(payload: ByteArray) {
        socket?.let { sock ->
            try {
                val pongFrame = createWebSocketFrame(payload, OPCODE_PONG)
                sock.getOutputStream().write(pongFrame)
                sock.getOutputStream().flush()
                logFrameVerbose("Sent WebSocket pong frame (${payload.size} bytes)")
            } catch (e: Exception) {
                logWarning("Failed to send pong frame: ${e.message}")
            }
        } ?: logWarning("Cannot send pong frame: socket is null")
    }
    
    private fun createWebSocketFrame(payload: ByteArray, opcode: Int): ByteArray {
        // 客户端发送的 WS 帧必须带 mask；此处统一封装，避免分散实现导致协议不一致。
        val payloadLength = payload.size
        
        // Generate random mask key (required for client-to-server frames)
        val maskKey = byteArrayOf(
            (Math.random() * 256).toInt().toByte(),
            (Math.random() * 256).toInt().toByte(),
            (Math.random() * 256).toInt().toByte(),
            (Math.random() * 256).toInt().toByte()
        )
        
        // Apply mask to payload
        val maskedPayload = ByteArray(payload.size)
        for (i in payload.indices) {
            maskedPayload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        
        val frame = when {
            payloadLength < PAYLOAD_EXTENDED_16 -> {
                val frameHeader = (MASK_BIT or payloadLength).toByte()
                byteArrayOf((FIN_BIT or opcode).toByte(), frameHeader) +
                maskKey + maskedPayload
            }
            payloadLength < 65536 -> {
                byteArrayOf((FIN_BIT or opcode).toByte(), (PAYLOAD_EXTENDED_16 or MASK_BIT).toByte()) +
                byteArrayOf((payloadLength shr 8).toByte(), payloadLength.toByte()) +
                maskKey + maskedPayload
            }
            else -> {
                // Large payload (>65535 bytes) - not expected for our use case
                throw IllegalArgumentException("Payload too large for simple WebSocket frame")
            }
        }
        return frame
    }
    
    fun disconnect() {
        logLifecycle("DISCONNECT")
        connected = false
        try {
            socket?.close()
        } catch (e: Exception) {
            logError("Error closing socket: ${e.message}")
        }
        socket = null
        
        // Cancel all coroutines started by this client
        try {
            scope.cancel()
        } catch (e: Exception) {
            logError("Error cancelling scope: ${e.message}")
        }
    }
    
    // WebSocket frame reassembly variables for handling fragmented binary messages
    private var frameBuffer: ByteArrayOutputStream? = null
    private var framingInProgress: Boolean = false
    private var expectedFrameType: Int = -1
    
    private fun readMooMessage(input: InputStream): String? {
        try {
            // Check if this is a WebSocket handshake response or WebSocket frame
            val firstByte = input.read()
            if (firstByte == -1) {
                logWarning("End of stream while reading")
                return null
            }
            
            // Check if it looks like HTTP response (starts with 'H' = 0x48)
            if (firstByte == 0x48) {
                logDebug("Reading HTTP handshake response...")
                
                // Read the rest of the HTTP response
                val buffer = StringBuilder()
                buffer.append(firstByte.toChar())
                
                // Read until we get double CRLF (end of HTTP headers)
                var lastChars = ""
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    
                    val char = b.toChar()
                    buffer.append(char)
                    lastChars += char
                    
                    // Keep only last 4 characters
                    if (lastChars.length > 4) {
                        lastChars = lastChars.substring(1)
                    }
                    
                    // Check for end of HTTP headers
                    if (lastChars == "\r\n\r\n") {
                        break
                    }
                }
                
                val response = buffer.toString()
                logDebug("HTTP response received: $response")
                return response
            }
            
            // Otherwise, treat as WebSocket frame
            val secondByte = input.read()
            if (secondByte == -1) {
                logWarning("End of stream while reading payload length")
                return null
            }
            
            val fin = (firstByte and 0x80) != 0
            val opcode = firstByte and 0x0F
            val masked = (secondByte and MASK_BIT) != 0
            var payloadLength = (secondByte and 0x7F).toLong()
            
            logFrameVerbose("WebSocket frame: fin=$fin, opcode=$opcode, masked=$masked, initial_length=$payloadLength")
            val isUnexpectedContinuation = opcode == OPCODE_CONTINUATION && !framingInProgress
            if (isUnexpectedContinuation) {
                logWarning("Received continuation frame but no fragmentation in progress")
            }
            if (opcode == OPCODE_TEXT || opcode == OPCODE_BINARY) {
                if (framingInProgress) {
                    logWarning("Starting new frame while fragmentation in progress, resetting buffer")
                    frameBuffer?.reset()
                    framingInProgress = false
                }
                expectedFrameType = opcode
            }
            
            // Handle extended payload length
            if (payloadLength == PAYLOAD_EXTENDED_16.toLong()) {
                val byte1 = input.read()
                val byte2 = input.read()
                if (byte1 == -1 || byte2 == -1) return null
                payloadLength = ((byte1 shl 8) or byte2).toLong()
            } else if (payloadLength == PAYLOAD_EXTENDED_64.toLong()) {
                // 64-bit length (not expected for Roon)
                for (i in 0..7) {
                    if (input.read() == -1) return null
                }
                logWarning("64-bit payload length not supported")
                return null
            }
            
            // Read mask key if present (server 按协议不该 mask，但做兼容读取避免解析错位)
            var maskKey: ByteArray? = null
            if (masked) {
                maskKey = ByteArray(4)
                for (i in 0 until maskKey.size) {
                    val maskByte = input.read()
                    if (maskByte == -1) return null
                    maskKey[i] = maskByte.toByte()
                }
            }

            val payload = readPayload(input, payloadLength) ?: return null
            if (masked && maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % maskKey.size].toInt()).toByte()
                }
            }
            
            when (opcode) {
                OPCODE_CLOSE -> {
                    logWarning("[WS][REMOTE_CLOSE_FRAME] close frame received")
                    return null
                }
                OPCODE_PING -> {
                    logFrameVerbose("Received WebSocket ping frame")
                    sendPongFrame(payload)
                    return readMooMessage(input)
                }
                OPCODE_PONG -> {
                    logFrameVerbose("Received WebSocket pong frame")
                    return readMooMessage(input)
                }
                OPCODE_TEXT, OPCODE_BINARY -> {
                    logFrameVerbose("WebSocket payload read: ${payload.size} bytes")
                    if (!fin) {
                        logFrameVerbose("Starting fragmented message reassembly")
                        frameBuffer = ByteArrayOutputStream()
                        frameBuffer!!.write(payload)
                        framingInProgress = true
                        return readMooMessage(input)
                    }
                    return processCompleteMessage(payload, opcode)
                }
                OPCODE_CONTINUATION -> {
                    if (isUnexpectedContinuation) {
                        return readMooMessage(input)
                    }
                    if (frameBuffer == null) {
                        frameBuffer = ByteArrayOutputStream()
                    }
                    frameBuffer!!.write(payload)
                    if (fin) {
                        logFrameVerbose("Fragmented message reassembly complete")
                        val completePayload = frameBuffer!!.toByteArray()
                        frameBuffer!!.close()
                        frameBuffer = null
                        framingInProgress = false
                        return processCompleteMessage(completePayload, expectedFrameType)
                    }
                    return readMooMessage(input)
                }
                else -> {
                    logWarning("Unknown WebSocket opcode: $opcode")
                    return readMooMessage(input)
                }
            }
            
        } catch (e: java.net.SocketTimeoutException) {
            // Re-throw timeout so caller can handle it (e.g. check loop condition)
            throw e
        } catch (e: Exception) {
            if (connected) {
                logError("Failed to read MOO message: ${e.message}", e)
            }
            // Reset frame buffer on error
            frameBuffer?.close()
            frameBuffer = null
            framingInProgress = false
            return null
        }
    }

    private fun readPayload(input: InputStream, payloadLength: Long): ByteArray? {
        if (payloadLength == 0L) return ByteArray(0)
        if (payloadLength > Int.MAX_VALUE) {
            logError("Payload too large: $payloadLength")
            return null
        }
        val payload = ByteArray(payloadLength.toInt())
        var totalRead = 0
        while (totalRead < payloadLength) {
            val bytesRead = input.read(payload, totalRead, (payloadLength - totalRead).toInt())
            if (bytesRead == -1) {
                logWarning("End of stream while reading payload")
                return null
            }
            totalRead += bytesRead
        }
        return payload
    }
    
    private fun processCompleteMessage(payload: ByteArray, frameType: Int): String {
        // For binary frame types (2) or when payload starts with binary markers, preserve as binary
        if (frameType == 2 || isBinaryData(payload)) {
            // Use ISO-8859-1 to preserve binary data without corruption
            return String(payload, Charsets.ISO_8859_1)
        } else {
            // Text frame - safe to use UTF-8
            return String(payload, Charsets.UTF_8)
        }
    }
    
    private fun isBinaryData(payload: ByteArray): Boolean {
        // Check for common binary markers
        if (payload.size >= 2) {
            val firstTwo = (payload[0].toInt() and 0xFF) to (payload[1].toInt() and 0xFF)
            // JPEG marker (FF D8)
            if (firstTwo.first == 0xFF && firstTwo.second == 0xD8) return true
            // PNG marker (89 50)
            if (firstTwo.first == 0x89 && firstTwo.second == 0x50) return true
        }
        
        // Check for high percentage of non-printable characters
        var nonPrintable = 0
        val sampleSize = minOf(100, payload.size)
        for (i in 0 until sampleSize) {
            val byte = payload[i].toInt() and 0xFF
            if (byte < 32 && byte != 9 && byte != 10 && byte != 13) {
                nonPrintable++
            }
        }
        
        return (nonPrintable.toFloat() / sampleSize) > 0.3
    }
    
}
