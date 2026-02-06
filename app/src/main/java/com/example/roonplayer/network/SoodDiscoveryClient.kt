package com.example.roonplayer.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class SoodDiscoveryClient(
    private val codec: SoodProtocolCodec = SoodProtocolCodec()
) {

    suspend fun discover(
        serviceId: String,
        targets: List<InetAddress>,
        discoveryPort: Int,
        socketTimeoutMs: Int,
        listenWindowMs: Long,
        onResponse: (payload: ByteArray, sourceIp: String) -> Unit,
        onLog: (String) -> Unit,
        onError: (String, Exception?) -> Unit
    ) {
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.reuseAddress = true
            socket.soTimeout = socketTimeoutMs

            val query = codec.buildServiceQuery(serviceId)
            val sentTargets = mutableSetOf<String>()
            for (target in targets) {
                val host = target.hostAddress ?: continue
                if (!sentTargets.add(host)) {
                    continue
                }

                try {
                    val packet = DatagramPacket(query, query.size, target, discoveryPort)
                    socket.send(packet)
                    onLog("Sent SOOD query to $target")
                } catch (sendError: Exception) {
                    onError("Failed to send SOOD query to $target", sendError)
                }
            }

            val buffer = ByteArray(RESPONSE_BUFFER_SIZE)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < listenWindowMs) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val payload = packet.data.copyOf(packet.length)
                    val sourceIp = packet.address.hostAddress ?: "unknown"
                    onResponse(payload, sourceIp)
                } catch (_: SocketTimeoutException) {
                    break
                } catch (receiveError: Exception) {
                    onError("SOOD receive error", receiveError)
                    break
                }
            }
        } finally {
            socket.close()
        }
    }

    companion object {
        private const val RESPONSE_BUFFER_SIZE = 1024
    }
}
