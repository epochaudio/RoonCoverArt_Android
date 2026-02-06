package com.example.roonplayer.domain

data class PairedCoreSnapshot(
    val host: String,
    val port: Int,
    val lastConnected: Long
)

data class ConnectionTarget(
    val host: String,
    val port: Int
)

sealed class ConnectionRecoveryStrategy {
    data class Connect(val target: ConnectionTarget) : ConnectionRecoveryStrategy()
    object Discover : ConnectionRecoveryStrategy()
    object NoOp : ConnectionRecoveryStrategy()
}

class ConnectionRoutingUseCase {

    fun strategyForDiscoveryStartup(
        pairedCores: Collection<PairedCoreSnapshot>
    ): ConnectionRecoveryStrategy {
        val latestPairedCore = latestPairedCore(pairedCores)
        return if (latestPairedCore != null) {
            ConnectionRecoveryStrategy.Connect(
                ConnectionTarget(
                    host = latestPairedCore.host,
                    port = latestPairedCore.port
                )
            )
        } else {
            ConnectionRecoveryStrategy.Discover
        }
    }

    fun strategyForAutoReconnection(
        autoReconnectAlreadyAttempted: Boolean,
        pairedCores: Collection<PairedCoreSnapshot>
    ): ConnectionRecoveryStrategy {
        // 自动重连是“最多一次”的防抖行为，避免网络抖动时重复触发连接风暴。
        if (autoReconnectAlreadyAttempted) {
            return ConnectionRecoveryStrategy.NoOp
        }

        if (pairedCores.isEmpty()) {
            return ConnectionRecoveryStrategy.NoOp
        }

        val latestPairedCore = latestPairedCore(pairedCores)
        return if (latestPairedCore != null) {
            ConnectionRecoveryStrategy.Connect(
                ConnectionTarget(
                    host = latestPairedCore.host,
                    port = latestPairedCore.port
                )
            )
        } else {
            ConnectionRecoveryStrategy.Discover
        }
    }

    private fun latestPairedCore(
        pairedCores: Collection<PairedCoreSnapshot>
    ): PairedCoreSnapshot? {
        return pairedCores.maxByOrNull { it.lastConnected }
    }
}
