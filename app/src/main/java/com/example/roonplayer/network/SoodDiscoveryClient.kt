package com.example.roonplayer.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
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
        includeInterfaceBroadcastTargets: Boolean = true,
        fallbackUnicastTargets: List<InetAddress> = emptyList(),
        onResponse: (payload: ByteArray, sourceIp: String) -> Unit,
        onLog: (String) -> Unit,
        onError: (String, Exception?) -> Unit
    ) {
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.reuseAddress = true
            socket.soTimeout = socketTimeoutMs

            val replyAddress = resolveReplyAddress()
            val query = codec.buildServiceQuery(
                serviceId = serviceId,
                replyAddress = replyAddress,
                replyPort = socket.localPort
            )
            onLog(
                "SOOD query prepared (replyaddr=${replyAddress ?: "none"}, " +
                    "replyport=${socket.localPort})"
            )

            val sentTargets = mutableSetOf<String>()
            val allTargets = LinkedHashSet<InetAddress>()
            allTargets.addAll(targets)
            allTargets.addAll(fallbackUnicastTargets)
            if (includeInterfaceBroadcastTargets) {
                allTargets.addAll(collectInterfaceBroadcastTargets())
            }

            for (target in allTargets) {
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

    private fun collectInterfaceBroadcastTargets(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }
            for (interfaceAddress in networkInterface.interfaceAddresses) {
                val broadcast = interfaceAddress.broadcast ?: continue
                if (broadcast is Inet4Address) {
                    addresses.add(broadcast)
                }
            }
        }
        return addresses
    }

    private fun resolveReplyAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    companion object {
        private const val RESPONSE_BUFFER_SIZE = 1024
    }
}
