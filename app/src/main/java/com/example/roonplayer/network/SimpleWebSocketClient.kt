package com.example.roonplayer.network

import com.example.roonplayer.MainActivity
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

// WebSocket客户端实现 - 使用Roon的官方WebSocket API
class SimpleWebSocketClient(
    private val host: String,
    private val port: Int,
    private val onMessage: (String) -> Unit
) {
    private var socket: Socket? = null
    private var connected = false
    
    companion object {
        private const val DEBUG_ENABLED = true
        private const val LOG_TAG = "RoonPlayer"
    }
    
    // Logging methods for SimpleWebSocketClient
    private fun logDebug(message: String) {
        if (DEBUG_ENABLED) android.util.Log.d(LOG_TAG, message)
    }
    
    private fun logInfo(message: String) {
        if (DEBUG_ENABLED) android.util.Log.i(LOG_TAG, message)
    }
    
    private fun logWarning(message: String) {
        if (DEBUG_ENABLED) android.util.Log.w(LOG_TAG, message)
    }
    
    private fun logError(message: String, e: Exception? = null) {
        if (DEBUG_ENABLED) android.util.Log.e(LOG_TAG, message, e)
    }
    
    fun isConnected(): Boolean = connected
    
    fun getHost(): String = host
    fun getPort(): Int = port
    
    // Use a custom CoroutineScope for managing background tasks
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Throws(Exception::class)
    fun connect() {
        logDebug("SimpleWebSocketClient.connect() to $host:$port using WebSocket protocol")
        try {
            // Ensure previous connection is cleaned up
            disconnect()
            
            // Reset scope
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            val newSocket = Socket()
            socket = newSocket
            logDebug("Socket object created")
            
            try {
                newSocket.connect(InetSocketAddress(host, port), 5000)
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
                sock.soTimeout = 5000 // Temporary timeout for handshake
                
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
                    logDebug("✅ WebSocket handshake successful")
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
                        logDebug("Starting MOO message listener loop")
                        
                        // Give a short delay to let the connection stabilize
                        delay(10)
                        
                        while (isActive && connected && !sock.isClosed && sock.isConnected) {
                            try {
                                // Set timeout for each read operation
                                sock.soTimeout = 15000 // 15 second timeout per read
                                
                                val message = readMooMessage(input)
                                if (message != null) {
                                    // Ignore HTTP response leftovers if any (handled in handshake)
                                    if (message.startsWith("HTTP/1.1")) {
                                        logDebug("Ignored duplicate handshake response")
                                        continue
                                    }
                                    
                                    logDebug("Received MOO message (${message.length} chars)")
                                    onMessage(message)
                                } else {
                                    logWarning("Received null message (EOF), connection closed by remote peer")
                                    break
                                }
                            } catch (e: java.net.SocketTimeoutException) {
                                // This is normal for read timeout if no data sent
                                continue
                            } catch (e: java.io.IOException) {
                                if (connected) {
                                    logError("IO error in message loop: ${e.message}")
                                    break
                                }
                            } catch (e: Exception) {
                                if (connected && e !is CancellationException) {
                                    logError("Unexpected error in message loop: ${e.message}", e)
                                }
                                break
                            }
                        }
                        logDebug("MOO message listener loop ended")
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            logError("MOO message listening failed: ${e.message}", e)
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
            logError("MOO connection failed: ${e.message}", e)
            connected = false
            throw e
        }
    }
    
    // 重连到现有的Roon Core，使用当前的连接参数
    fun connectToExistingCore(): Boolean {
        return try {
            logDebug("🔄 Attempting to reconnect to $host:$port")
            
            // 断开现有连接 (cancel old scope)
            disconnect()
            
            // Reset scope for new connection
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // 重新建立socket连接
            val newSocket = Socket()
            try {
                newSocket.connect(InetSocketAddress(host, port), 10000) // 10秒超时
            } catch (e: Exception) {
                try { newSocket.close() } catch (ignore: Exception) {}
                logError("❌ Failed to connect reconnection socket: ${e.message}")
                return false
            }
            
            socket = newSocket
            val sock = newSocket
            if (!sock.isConnected) {
                logError("❌ Socket not connected after creation")
                try { sock.close() } catch (ignore: Exception) {}
                socket = null
                return false
            }
            
            connected = true
            logDebug("✅ Socket reconnected successfully")
            
            // 发送WebSocket握手 - 使用标准Roon API路径
            val websocketKey = "dGhlIHNhbXBsZSBub25jZQ=="
            val handshake = buildString {
                append("GET ${MainActivity.ROON_WS_PATH} HTTP/1.1\r\n")  // 使用标准Roon API路径
                append("Host: $host\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $websocketKey\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("User-Agent: RoonPlayerAndroid/1.0\r\n")
                append("\r\n")
            }
            
            sock.getOutputStream().write(handshake.toByteArray())
            sock.getOutputStream().flush()
            
            // 读取握手响应
            sock.soTimeout = 5000
            val input = sock.getInputStream()
            val buffer = ByteArray(1024)
            val bytesRead = input.read(buffer)
            
            if (bytesRead > 0) {
                val response = String(buffer, 0, bytesRead)
                if (response.contains("101 Switching Protocols")) {
                    logDebug("✅ WebSocket handshake successful on reconnection")
                    
                    // 重启消息监听循环
                    scope.launch {
                        try {
                            delay(100)
                            logDebug("🔄 Restarting message listener after reconnection")
                            
                            while (isActive && connected && !sock.isClosed && sock.isConnected) {
                                try {
                                    sock.soTimeout = 15000
                                    val message = readMooMessage(input)
                                    if (message != null) {
                                        onMessage(message)
                                    } else {
                                        if (connected) {
                                            logWarning("Null message in reconnected listener")
                                            break
                                        }
                                    }
                                } catch (e: java.net.SocketTimeoutException) {
                                    continue
                                } catch (e: Exception) {
                                    if (connected && e !is CancellationException) {
                                        logError("Error in reconnected listener: ${e.message}")
                                    }
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException) {
                                logError("Reconnected listener failed: ${e.message}")
                            }
                        }
                    }
                    
                    return true
                } else {
                    logError("❌ WebSocket handshake failed on reconnection")
                    return false
                }
            } else {
                logError("❌ No handshake response on reconnection")
                return false
            }
        } catch (e: Exception) {
            logError("❌ Reconnection failed: ${e.message}", e)
            connected = false
            return false
        }
    }
    
    fun send(message: String) {
        socket?.let { sock ->
            try {
                logDebug("Sending raw TCP message: $message")
                val messageBytes = message.toByteArray(Charsets.UTF_8)
                logDebug("Message bytes (${messageBytes.size}): ${messageBytes.joinToString(" ") { "%02x".format(it) }}")
                
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
                logDebug("Sending WebSocket frame: $message")
                val messageBytes = message.toByteArray(Charsets.UTF_8)
                
                // Create WebSocket frame (simple text frame)
                val frame = createWebSocketFrame(messageBytes)
                
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
    
    private fun createWebSocketFrame(payload: ByteArray): ByteArray {
        // Create a properly masked WebSocket text frame
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
            payloadLength < 126 -> {
                // Small payload (0-125 bytes) - Use binary frame (0x82) instead of text (0x81)
                byteArrayOf(0x82.toByte(), (payloadLength or 0x80).toByte()) + 
                maskKey + maskedPayload
            }
            payloadLength < 65536 -> {
                // Medium payload (126-65535 bytes) - Use binary frame (0x82)
                byteArrayOf(0x82.toByte(), (126 or 0x80).toByte()) +
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
            logDebug("Reading WebSocket frame...")
            
            val secondByte = input.read()
            if (secondByte == -1) {
                logWarning("End of stream while reading payload length")
                return null
            }
            
            val fin = (firstByte and 0x80) != 0
            val opcode = firstByte and 0x0F
            val masked = (secondByte and 0x80) != 0
            var payloadLength = (secondByte and 0x7F).toLong()
            
            logDebug("WebSocket frame: fin=$fin, opcode=$opcode, masked=$masked, initial_length=$payloadLength")
            
            // Handle different WebSocket frame types
            when (opcode) {
                0 -> {
                    // Continuation frame - part of fragmented message
                    logDebug("Received WebSocket continuation frame")
                    if (!framingInProgress) {
                        logWarning("Received continuation frame but no fragmentation in progress")
                        return readMooMessage(input) // Skip this frame and read next
                    }
                }
                1, 2 -> {
                    // Text or binary frame - start of new message
                    logDebug("Received WebSocket data frame")
                    if (framingInProgress) {
                        logWarning("Starting new frame while fragmentation in progress, resetting buffer")
                        frameBuffer?.reset()
                        framingInProgress = false
                    }
                    expectedFrameType = opcode
                }
                8 -> {
                    // Close frame
                    logWarning("Received WebSocket close frame")
                    return null
                }
                9 -> {
                    // Ping frame
                    logDebug("Received WebSocket ping frame")
                }
                10 -> {
                    // Pong frame
                    logDebug("Received WebSocket pong frame")
                }
                else -> {
                    logWarning("Unknown WebSocket opcode: $opcode")
                }
            }
            
            // Handle extended payload length
            if (payloadLength == 126L) {
                val byte1 = input.read()
                val byte2 = input.read()
                if (byte1 == -1 || byte2 == -1) return null
                payloadLength = ((byte1 shl 8) or byte2).toLong()
            } else if (payloadLength == 127L) {
                // 64-bit length (not expected for Roon)
                for (i in 0..7) {
                    if (input.read() == -1) return null
                }
                logWarning("64-bit payload length not supported")
                return null
            }
            
            // Read mask key if present (server shouldn't send masked frames)
            if (masked) {
                for (i in 0..3) {
                    if (input.read() == -1) return null
                }
            }
            
            // Read payload only for data frames and continuation frames
            if (opcode == 0 || opcode == 1 || opcode == 2) {
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
                
                logDebug("WebSocket payload read: ${payload.size} bytes")
                
                // Handle frame reassembly for fragmented messages
                if (opcode == 1 || opcode == 2) {
                    // Start of new message
                    if (!fin) {
                        // This is the first frame of a fragmented message
                        logDebug("Starting fragmented message reassembly")
                        frameBuffer = ByteArrayOutputStream()
                        frameBuffer!!.write(payload)
                        framingInProgress = true
                        return readMooMessage(input) // Continue reading next frame
                    } else {
                        // Complete single frame message
                        return processCompleteMessage(payload, opcode)
                    }
                } else if (opcode == 0) {
                    // Continuation frame
                    if (frameBuffer == null) {
                        frameBuffer = ByteArrayOutputStream()
                    }
                    frameBuffer!!.write(payload)
                    
                    if (fin) {
                        // This is the final frame, reassemble complete message
                        logDebug("Fragmented message reassembly complete")
                        val completePayload = frameBuffer!!.toByteArray()
                        frameBuffer!!.close()
                        frameBuffer = null
                        framingInProgress = false
                        
                        return processCompleteMessage(completePayload, expectedFrameType)
                    } else {
                        // More frames to come
                        logDebug("Continuing fragmented message reassembly")
                        return readMooMessage(input) // Continue reading next frame
                    }
                }
            }
            
            // Handle close frames specially
            if (opcode == 8) {
                logWarning("Connection closed by server")
                return null
            }
            
            // For other frame types (ping, pong, etc), continue reading
            logDebug("Non-data frame, continuing to read...")
            return readMooMessage(input)
            
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
    
    private fun processCompleteMessage(payload: ByteArray, frameType: Int): String {
        logDebug("Processing complete message: ${payload.size} bytes, frameType=$frameType")
        
        // For binary frame types (2) or when payload starts with binary markers, preserve as binary
        if (frameType == 2 || isBinaryData(payload)) {
            logDebug("Processing as binary data")
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
