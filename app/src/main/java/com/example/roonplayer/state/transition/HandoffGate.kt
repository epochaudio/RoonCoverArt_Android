package com.example.roonplayer.state.transition

class HandoffGate(
    private val activeKeyProvider: () -> CorrelationKey
) {

    fun canCommit(intentKey: CorrelationKey): Boolean {
        return activeKeyProvider() == intentKey
    }
}
