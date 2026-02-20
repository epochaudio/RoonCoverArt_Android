package com.example.roonplayer.state.transition

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

data class TransitionAnimationSession(
    val sessionId: Long,
    val key: CorrelationKey,
    val phase: UiPhase,
    val direction: TransitionDirection,
    val targetTrack: TransitionTrack,
    val startedAtMs: Long
) {
    private val handoffCommitted = AtomicBoolean(false)
    private val committedFields = Collections.synchronizedSet(mutableSetOf<String>())

    fun commitHandoffOnce(action: () -> Unit): Boolean {
        if (!handoffCommitted.compareAndSet(false, true)) {
            return false
        }
        action()
        return true
    }

    fun commitFieldOnce(
        fieldId: String,
        action: () -> Unit
    ): Boolean {
        val added = committedFields.add(fieldId)
        if (!added) {
            return false
        }
        action()
        return true
    }
}
