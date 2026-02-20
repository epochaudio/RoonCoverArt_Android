# Track Transition Refactor Checklist (2026-02-20)

## Goals
- Build a provable track-transition core with invariants and pure reducer semantics.
- Enforce last-intent-wins via CorrelationKey and stale callback drop.
- Add single-writer store, idempotent effect handling, snapshot durability contract, and progress sync policy.
- Validate with contract and chaos-order tests.

## Task Checklist
- [x] Define core protocol models and design tokens.
- [x] Implement pure reducer with invariant assertions.
- [x] Implement single-writer event-loop store.
- [x] Implement idempotent effect handler and token gate.
- [x] Add committed snapshot repository contract.
- [x] Add SharedPreferences-backed committed snapshot persistence.
- [x] Add dual-threshold progress synchronization policy.
- [x] Add reducer/effect/store/progress unit tests.
- [x] Integrate transition store into `MainActivity` next/previous + now playing update flow.
- [x] Replace transition-related hardcoded animation timings with design tokens.
- [x] Add state-driven transition rendering (cover/text/palette) with rollback-safe animation completion.
- [x] Run project unit tests to verify compatibility.

## Verification
- Command: `./gradlew testDebugUnitTest`
- Result: `BUILD SUCCESSFUL`
