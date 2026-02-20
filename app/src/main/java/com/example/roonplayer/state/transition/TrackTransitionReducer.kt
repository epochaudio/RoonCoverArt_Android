package com.example.roonplayer.state.transition

class TrackTransitionReducer {

    fun reduce(
        previous: TrackTransitionState,
        intent: TrackTransitionIntent
    ): TrackTransitionReduction {
        val reduction = when (intent) {
            is TrackTransitionIntent.Skip -> onSkip(previous, intent)
            is TrackTransitionIntent.EngineUpdate -> onEngineEvent(previous, intent.event)
            is TrackTransitionIntent.AnimationCompleted -> onAnimationCompleted(previous, intent)
            is TrackTransitionIntent.HydrateCommittedSnapshot -> onHydrateCommittedSnapshot(previous, intent)
        }
        assertInvariants(reduction.state)
        return reduction
    }

    private fun onSkip(
        previous: TrackTransitionState,
        intent: TrackTransitionIntent.Skip
    ): TrackTransitionReduction {
        val command = when (intent.direction) {
            TransitionDirection.NEXT -> EngineCommand.SKIP_NEXT
            TransitionDirection.PREVIOUS -> EngineCommand.SKIP_PREVIOUS
            TransitionDirection.UNKNOWN -> EngineCommand.PLAY_TRACK
        }
        val nextState = previous.copy(
            currentKey = intent.key,
            displayTrack = intent.targetTrack,
            optimisticTrack = intent.targetTrack,
            phase = UiPhase.OPTIMISTIC_MORPHING,
            transitionDirection = intent.direction,
            audioReady = false,
            activeTransitionCount = 1
        )
        return TrackTransitionReduction(
            state = nextState,
            effects = setOf(
                TrackTransitionEffect.CommandEngine(
                    correlationKey = intent.key,
                    command = command,
                    track = intent.targetTrack
                )
            )
        )
    }

    private fun onEngineEvent(
        previous: TrackTransitionState,
        event: EngineEvent
    ): TrackTransitionReduction {
        if (event.key.isStale(previous.currentKey)) {
            return TrackTransitionReduction(state = previous, effects = emptySet())
        }

        return when (event) {
            is EngineEvent.Buffering -> {
                val state = previous.copy(
                    displayTrack = previous.displayTrack ?: event.track,
                    optimisticTrack = previous.optimisticTrack ?: event.track,
                    audioReady = false
                )
                TrackTransitionReduction(state, emptySet())
            }

            is EngineEvent.Playing -> {
                val nextPhase = when (previous.phase) {
                    UiPhase.OPTIMISTIC_MORPHING -> UiPhase.OPTIMISTIC_MORPHING
                    UiPhase.ROLLING_BACK -> UiPhase.ROLLING_BACK
                    UiPhase.AWAITING_ENGINE -> UiPhase.STABLE
                    UiPhase.STABLE -> UiPhase.STABLE
                }
                val activeTransitionCount = when (nextPhase) {
                    UiPhase.OPTIMISTIC_MORPHING,
                    UiPhase.ROLLING_BACK -> 1
                    UiPhase.STABLE,
                    UiPhase.AWAITING_ENGINE -> 0
                }
                val nextDisplayTrack = when (nextPhase) {
                    UiPhase.STABLE -> event.track
                    UiPhase.OPTIMISTIC_MORPHING,
                    UiPhase.ROLLING_BACK,
                    UiPhase.AWAITING_ENGINE -> previous.displayTrack ?: event.track
                }
                val nextOptimisticTrack = when (nextPhase) {
                    UiPhase.STABLE -> null
                    UiPhase.OPTIMISTIC_MORPHING,
                    UiPhase.ROLLING_BACK,
                    UiPhase.AWAITING_ENGINE -> previous.optimisticTrack
                }
                val nextDirection = when (nextPhase) {
                    UiPhase.STABLE -> TransitionDirection.UNKNOWN
                    UiPhase.OPTIMISTIC_MORPHING,
                    UiPhase.ROLLING_BACK,
                    UiPhase.AWAITING_ENGINE -> previous.transitionDirection
                }
                val nextState = previous.copy(
                    committedTrack = event.track,
                    displayTrack = nextDisplayTrack,
                    optimisticTrack = nextOptimisticTrack,
                    phase = nextPhase,
                    transitionDirection = nextDirection,
                    audioReady = true,
                    activeTransitionCount = activeTransitionCount
                )

                val snapshot = CommittedPlaybackSnapshot(
                    sessionId = event.key.sessionId,
                    queueVersion = event.key.queueVersion,
                    track = event.track,
                    anchorPositionMs = event.anchorPositionMs,
                    anchorRealtimeMs = event.anchorRealtimeMs
                )
                TrackTransitionReduction(
                    state = nextState,
                    effects = setOf(
                        TrackTransitionEffect.PersistCommittedSnapshot(snapshot),
                        TrackTransitionEffect.EmitMetric(
                            correlationKey = event.key,
                            name = "track_transition_playing_confirmed"
                        )
                    )
                )
            }

            is EngineEvent.Error -> {
                val fallback = previous.committedTrack
                if (fallback != null) {
                    val nextState = previous.copy(
                        displayTrack = fallback,
                        optimisticTrack = null,
                        phase = UiPhase.ROLLING_BACK,
                        transitionDirection = previous.transitionDirection,
                        audioReady = false,
                        activeTransitionCount = 1
                    )
                    TrackTransitionReduction(
                        state = nextState,
                        effects = setOf(
                            TrackTransitionEffect.EmitMetric(
                                correlationKey = event.key,
                                name = "track_transition_rollback_${event.failure.category.name.lowercase()}"
                            )
                        )
                    )
                } else {
                    val nextState = previous.copy(
                        displayTrack = event.failedTrack,
                        optimisticTrack = null,
                        phase = UiPhase.STABLE,
                        transitionDirection = TransitionDirection.UNKNOWN,
                        audioReady = false,
                        activeTransitionCount = 0
                    )
                    TrackTransitionReduction(
                        state = nextState,
                        effects = setOf(
                            TrackTransitionEffect.EmitMetric(
                                correlationKey = event.key,
                                name = "track_transition_error_without_fallback"
                            )
                        )
                    )
                }
            }
        }
    }

    private fun onAnimationCompleted(
        previous: TrackTransitionState,
        intent: TrackTransitionIntent.AnimationCompleted
    ): TrackTransitionReduction {
        if (intent.key.isStale(previous.currentKey)) {
            return TrackTransitionReduction(state = previous, effects = emptySet())
        }

        val nextState = when (previous.phase) {
            UiPhase.OPTIMISTIC_MORPHING -> {
                if (previous.audioReady) {
                    previous.copy(
                        displayTrack = previous.committedTrack ?: previous.displayTrack,
                        optimisticTrack = null,
                        phase = UiPhase.STABLE,
                        transitionDirection = TransitionDirection.UNKNOWN,
                        activeTransitionCount = 0
                    )
                } else {
                    previous.copy(
                        phase = UiPhase.AWAITING_ENGINE,
                        activeTransitionCount = 0
                    )
                }
            }

            UiPhase.ROLLING_BACK -> {
                previous.copy(
                    displayTrack = previous.committedTrack,
                    optimisticTrack = null,
                    phase = UiPhase.STABLE,
                    transitionDirection = TransitionDirection.UNKNOWN,
                    activeTransitionCount = 0
                )
            }

            UiPhase.AWAITING_ENGINE,
            UiPhase.STABLE -> previous
        }

        return TrackTransitionReduction(state = nextState, effects = emptySet())
    }

    private fun onHydrateCommittedSnapshot(
        previous: TrackTransitionState,
        intent: TrackTransitionIntent.HydrateCommittedSnapshot
    ): TrackTransitionReduction {
        val snapshot = intent.snapshot
        val nextState = previous.copy(
            currentKey = previous.currentKey.copy(
                sessionId = snapshot.sessionId,
                queueVersion = snapshot.queueVersion
            ),
            committedTrack = snapshot.track,
            displayTrack = snapshot.track,
            optimisticTrack = null,
            phase = UiPhase.STABLE,
            transitionDirection = TransitionDirection.UNKNOWN,
            audioReady = true,
            activeTransitionCount = 0
        )
        return TrackTransitionReduction(nextState, emptySet())
    }

    private fun assertInvariants(state: TrackTransitionState) {
        check(state.activeTransitionCount in 0..1) {
            "activeTransitionCount must be 0 or 1"
        }

        when (state.phase) {
            UiPhase.OPTIMISTIC_MORPHING,
            UiPhase.ROLLING_BACK -> {
                check(state.activeTransitionCount == 1) {
                    "phase ${state.phase} requires activeTransitionCount = 1"
                }
            }

            UiPhase.AWAITING_ENGINE,
            UiPhase.STABLE -> {
                check(state.activeTransitionCount == 0) {
                    "phase ${state.phase} requires activeTransitionCount = 0"
                }
            }
        }

        if (state.phase == UiPhase.ROLLING_BACK) {
            check(state.committedTrack != null) {
                "ROLLING_BACK requires committedTrack"
            }
        }

        if (state.phase == UiPhase.AWAITING_ENGINE) {
            check(state.optimisticTrack != null) {
                "AWAITING_ENGINE requires optimisticTrack"
            }
        }

        val display = state.displayTrack
        if (display != null) {
            val matchesCommitted = display == state.committedTrack
            val matchesOptimistic = display == state.optimisticTrack
            check(matchesCommitted || matchesOptimistic) {
                "displayTrack must come from committedTrack or optimisticTrack"
            }
        }
    }
}
