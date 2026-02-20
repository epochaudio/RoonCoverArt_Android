package com.example.roonplayer.state.transition

import java.util.Collections

fun interface TrackTransitionEffectHandler {
    suspend fun handle(effect: TrackTransitionEffect)
}

interface EffectIdempotencyGate {
    /**
     * @return true when the token is newly marked.
     */
    fun markIfAbsent(token: String): Boolean
}

class InMemoryEffectIdempotencyGate : EffectIdempotencyGate {
    private val processed = Collections.synchronizedSet(mutableSetOf<String>())

    override fun markIfAbsent(token: String): Boolean {
        return processed.add(token)
    }
}

class IdempotentTrackTransitionEffectHandler(
    private val delegate: TrackTransitionEffectHandler,
    private val gate: EffectIdempotencyGate = InMemoryEffectIdempotencyGate()
) : TrackTransitionEffectHandler {

    override suspend fun handle(effect: TrackTransitionEffect) {
        val token = effect.idempotencyToken()
        if (token == null || gate.markIfAbsent(token)) {
            delegate.handle(effect)
        }
    }
}

fun TrackTransitionEffect.idempotencyToken(): String? {
    return when (this) {
        is TrackTransitionEffect.CommandEngine -> {
            "engine:${correlationKey.token()}:${command.name}:${track.id}"
        }

        is TrackTransitionEffect.EmitMetric -> {
            "metric:${correlationKey.token()}:$name"
        }

        is TrackTransitionEffect.PersistCommittedSnapshot -> {
            "snapshot:${snapshot.sessionId}:${snapshot.queueVersion}:${snapshot.track.id}:${snapshot.anchorPositionMs}"
        }
    }
}
