# Track Transition Choreography Hardening Checklist (2026-02-20)

## Scope
- Keep current layout and controls unchanged.
- Improve transition feel using state-safe choreography only.
- Harden against stale async callbacks, duplicate handoff commits, direction mismatch, and rollback jitter.

## Tasks
- [x] Add choreography tokens for handoff timing, text cascade, direction vectors, rollback, and tint cues.
  - Acceptance: No new hardcoded transition timings in `MainActivity` for track transition flow.
  - Result: Added in `TrackTransitionDesignTokens` (`CoverTransition`, `TextTransition`, `DirectionVectors`, `Rollback`, `Palette`).

- [x] Add safeguard primitives:
  - `HandoffGate` for key validation before delayed binding commit.
  - `TransitionAnimationSession` for idempotent handoff execution.
  - `DirectionalVectorPolicy` for unified NEXT/PREV/ROLLBACK motion and cascade order.
  - Acceptance: Primitives live under `state/transition` and are reusable.
  - Result: Added under `app/src/main/java/com/example/roonplayer/state/transition/`.

- [x] Refactor track text choreography to delayed binding (handoff commit) instead of immediate mapping.
  - Acceptance: Text updates occur at handoff point, not at state arrival.
  - Acceptance: Old delayed callbacks are dropped when key/session becomes stale.
  - Result: `animateTrackTextTransition(session, motion)` now gates each delayed stage with `isSessionActive(session)` + idempotent commit guards.

- [x] Refactor cover choreography to anticipation->handoff->follow-through with session-bound completion.
  - Acceptance: Cover transition can be interrupted safely by newer intents.
  - Result: `animateTrackTransition(session, motion)` uses session-scoped commit and cancel-safe completion checks.

- [x] Implement rollback interrupt-and-reverse behavior.
  - Acceptance: On rollback, running transition is canceled and views reverse from current transform state.
  - Acceptance: Use background tint cue via existing background drawable, no new UI widgets.
  - Result: Rollback phase drives reverse cover/text motion and `animateRollbackTintCue()` over existing `mainLayout` background drawable.

- [x] Validate build/tests and update this checklist to completed.
  - Acceptance: `./gradlew testDebugUnitTest --tests "com.example.roonplayer.state.transition.*"` passes.
  - Acceptance: `./gradlew testDebugUnitTest` passes.
  - Result: Both commands passed on 2026-02-20.
