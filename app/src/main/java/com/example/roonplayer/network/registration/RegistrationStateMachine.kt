package com.example.roonplayer.network.registration

enum class RegistrationState {
    Idle,
    FetchingInfo,
    Registering,
    WaitingApproval,
    Registered,
    Failed
}

data class RegistrationSnapshot(
    val state: RegistrationState,
    val coreId: String?,
    val token: String?,
    val error: String?
)

class RegistrationStateMachine {
    private var snapshot = RegistrationSnapshot(
        state = RegistrationState.Idle,
        coreId = null,
        token = null,
        error = null
    )

    fun snapshot(): RegistrationSnapshot = snapshot

    fun onInfoRequested(): RegistrationSnapshot {
        snapshot = snapshot.copy(
            state = RegistrationState.FetchingInfo,
            error = null
        )
        return snapshot
    }

    fun onInfoReceived(coreId: String): RegistrationSnapshot {
        snapshot = snapshot.copy(
            state = RegistrationState.Registering,
            coreId = coreId,
            error = null
        )
        return snapshot
    }

    fun onRegistrationSucceeded(token: String): RegistrationSnapshot {
        snapshot = snapshot.copy(
            state = RegistrationState.Registered,
            token = token,
            error = null
        )
        return snapshot
    }

    fun onWaitingApproval(): RegistrationSnapshot {
        snapshot = snapshot.copy(
            state = RegistrationState.WaitingApproval,
            error = null
        )
        return snapshot
    }

    fun onFailure(message: String): RegistrationSnapshot {
        snapshot = snapshot.copy(
            state = RegistrationState.Failed,
            error = message
        )
        return snapshot
    }
}
