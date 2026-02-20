package com.example.roonplayer.state.transition

import java.util.concurrent.atomic.AtomicLong

enum class TransitionDirection {
    NEXT,
    PREVIOUS,
    UNKNOWN
}

enum class UiPhase {
    STABLE,
    OPTIMISTIC_MORPHING,
    AWAITING_ENGINE,
    ROLLING_BACK
}

data class TransitionTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val imageKey: String? = null
)

data class CorrelationKey(
    val sessionId: String,
    val queueVersion: Long,
    val intentId: Long
) {
    fun isStale(currentKey: CorrelationKey): Boolean {
        return this != currentKey
    }

    fun token(): String {
        return "$sessionId:$queueVersion:$intentId"
    }
}

class CorrelationKeyFactory(
    private val sessionIdProvider: () -> String,
    private val queueVersionProvider: () -> Long,
    private val counter: AtomicLong = AtomicLong(0L)
) {
    fun next(): CorrelationKey {
        return CorrelationKey(
            sessionId = sessionIdProvider(),
            queueVersion = queueVersionProvider(),
            intentId = counter.incrementAndGet()
        )
    }
}

enum class PlaybackFailureCategory {
    RETRYABLE,
    NON_RETRYABLE,
    GEO_BLOCKED,
    DRM,
    TIMEOUT
}

data class PlaybackFailure(
    val category: PlaybackFailureCategory,
    val message: String
)

sealed class EngineEvent(open val key: CorrelationKey) {
    data class Buffering(
        override val key: CorrelationKey,
        val track: TransitionTrack
    ) : EngineEvent(key)

    data class Playing(
        override val key: CorrelationKey,
        val track: TransitionTrack,
        val anchorPositionMs: Long,
        val anchorRealtimeMs: Long
    ) : EngineEvent(key)

    data class Error(
        override val key: CorrelationKey,
        val failedTrack: TransitionTrack,
        val failure: PlaybackFailure
    ) : EngineEvent(key)
}

sealed class TrackTransitionIntent {
    data class Skip(
        val key: CorrelationKey,
        val direction: TransitionDirection,
        val targetTrack: TransitionTrack
    ) : TrackTransitionIntent()

    data class EngineUpdate(val event: EngineEvent) : TrackTransitionIntent()

    data class AnimationCompleted(val key: CorrelationKey) : TrackTransitionIntent()

    data class HydrateCommittedSnapshot(
        val snapshot: CommittedPlaybackSnapshot
    ) : TrackTransitionIntent()
}

enum class EngineCommand {
    SKIP_NEXT,
    SKIP_PREVIOUS,
    PLAY_TRACK
}

data class CommittedPlaybackSnapshot(
    val sessionId: String,
    val queueVersion: Long,
    val track: TransitionTrack,
    val anchorPositionMs: Long,
    val anchorRealtimeMs: Long
)

sealed class TrackTransitionEffect {
    abstract val correlationKey: CorrelationKey?

    data class CommandEngine(
        override val correlationKey: CorrelationKey,
        val command: EngineCommand,
        val track: TransitionTrack
    ) : TrackTransitionEffect()

    data class PersistCommittedSnapshot(
        val snapshot: CommittedPlaybackSnapshot
    ) : TrackTransitionEffect() {
        override val correlationKey: CorrelationKey? = null
    }

    data class EmitMetric(
        override val correlationKey: CorrelationKey,
        val name: String,
        val value: Long = 1L
    ) : TrackTransitionEffect()
}

data class TrackTransitionState(
    val currentKey: CorrelationKey,
    val committedTrack: TransitionTrack?,
    val displayTrack: TransitionTrack?,
    val optimisticTrack: TransitionTrack?,
    val phase: UiPhase,
    val transitionDirection: TransitionDirection,
    val audioReady: Boolean,
    val activeTransitionCount: Int
) {
    companion object {
        fun initial(sessionId: String = "session-0"): TrackTransitionState {
            return TrackTransitionState(
                currentKey = CorrelationKey(sessionId = sessionId, queueVersion = 0L, intentId = 0L),
                committedTrack = null,
                displayTrack = null,
                optimisticTrack = null,
                phase = UiPhase.STABLE,
                transitionDirection = TransitionDirection.UNKNOWN,
                audioReady = false,
                activeTransitionCount = 0
            )
        }
    }
}

data class TrackTransitionReduction(
    val state: TrackTransitionState,
    val effects: Set<TrackTransitionEffect>
)
