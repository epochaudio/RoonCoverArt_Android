# Track Text Layout Migration Task List

## Purpose

This document turns the earlier `pretext -> Android` design discussion into an executable development plan for `CoverArtForAndroid`.

The target outcome is to replace the current `TextView`-driven track metadata rendering with a scene-based text layout and rendering pipeline that:

- measures text before rendering,
- keeps layout decisions out of animation code,
- enables scene-to-scene text transitions without runtime relayout jumps,
- supports future premium details such as shrink-wrap backgrounds and per-line effects.

## Scope

In scope:

- track title / artist / album rendering,
- layout policy extraction,
- Android text measurement engine based on `TextPaint` and `StaticLayout`,
- custom scene rendering view,
- transition choreographer migration,
- `MainActivity` duplicate text-animation path removal,
- palette pipeline migration,
- runtime feature flag rollout for safe cutover,
- tests for policy and state-safe integration seams.

Out of scope for the first delivery:

- lyrics or scrolling marquee behavior,
- rich text spans,
- arbitrary font loading system,
- instrumentation test infrastructure expansion unless JVM coverage proves insufficient,
- redesign of album art transition behavior.

## Current Status (2026-03-29)

This migration has already landed in the current tree. The codebase now:

- routes metadata text through `TrackTextLayoutPolicy`, `AndroidTrackTextLayoutEngine`, `TrackTextScene`, and `TrackTextSceneView`,
- keeps `TrackState` as raw title / artist / album strings while deriving measured scenes from current bounds on demand,
- renders both portrait and landscape metadata through the same scene-only path,
- drives text transitions by scene progress interpolation instead of live `TextView` mutation,
- applies metadata colors through explicit `TrackTextPalette` ownership while keeping `statusText` on a separate overlay path,
- covers policy behavior and transition-state bookkeeping with JVM tests in `TrackTextLayoutPolicyTest` and `TrackTextSceneTransitionStateTest`.

Still pending or intentionally deferred:

- physical-device readability QA on dark, bright, and low-contrast album art,
- `TextMeasurer` / measurement-first policy integration,
- `breakStrategy` / `hyphenationFrequency` tuning and second-pass balanced shrink-wrap,
- optional follow-up cleanup of remaining non-authoritative layout constants.

Historical note:

- The staged feature-flag rollout described below is kept as implementation history.
- The current tree no longer contains the temporary metadata feature flag or the legacy metadata `TextView` renderer.

## Historical Pressure Points

The migration started from an implementation that bound text directly into `TextView` instances and mutated them during rendering and transitions:

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`
- `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionChoreographer.kt`
- `app/src/main/java/com/example/roonplayer/PaletteManager.kt`

This creates four concrete problems:

1. layout decisions are embedded in view properties instead of explicit policy,
2. transition code mutates text content mid-animation,
3. palette propagation depends on traversing `TextView` nodes,
4. future visual treatments depend on rectangular view bounds rather than line metrics.

There are also three migration-specific risks already visible in the current code:

1. there are two active metadata text-animation implementations,
2. palette updates can unintentionally affect `statusText`,
3. orientation relayout still assumes three distinct metadata `TextView`s.

## Target Architecture

The end state should look like this:

1. `TrackTextLayoutPolicy`
   Decides fallback rules and sizing priorities.
2. `AndroidTrackTextLayoutEngine`
   Measures and shapes text into immutable layout results.
3. `TrackTextScene`
   Carries the ready-to-render scene for title, artist, and album.
4. `TrackTextSceneView`
   Draws one stable scene or interpolates between two scenes.
5. `TrackTransitionChoreographer`
   Animates numeric transition progress only.
6. `PaletteManager`
   Produces palette data, not `TextView` mutations.

## Delivery Strategy

Recommended rollout model:

1. Introduce policy and models first. This maps to Milestone 1.
2. Add Android measurement engine and gate the new path behind a runtime feature flag. This maps to Milestone 2.
3. Run the engine in shadow mode without changing the visible UI. This also maps to Milestone 2.
4. Isolate status text color ownership before any scene-view rollout. This is a Milestone 2 prerequisite, not a separate milestone.
5. Add a custom scene view behind the same runtime flag while preserving the legacy `TextView` path when the flag is off. This maps to Milestone 3.
6. Migrate all transition animation paths from `TextView` mutation to scene interpolation. This maps to Milestone 4.
7. Migrate palette and remove obsolete `TextView` coupling. This maps to Milestone 5.
8. Add premium visual details only after the new rendering path is stable. This maps to Milestone 6.

## File Map

### New files

- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicy.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutModels.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextScene.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextPalette.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/AndroidTrackTextLayoutEngine.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextSceneView.kt`
- `app/src/test/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicyTest.kt`
- `app/src/test/java/com/example/roonplayer/ui/text/TrackTextSceneTransitionStateTest.kt`

### Existing files expected to change

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`
- `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionChoreographer.kt`
- `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionDesignTokens.kt`
- `app/src/main/java/com/example/roonplayer/PaletteManager.kt`
- `app/src/main/java/com/example/roonplayer/LayoutOrchestrator.kt`
- `app/src/main/java/com/example/roonplayer/config/AppRuntimeConfig.kt`

Optional later files:

- `README.md`

## Milestone Breakdown

## Milestone 0: Baseline and Guardrails

### Goal

Capture current behavior and establish the edge cases the new pipeline must preserve or improve.

### Tasks

- Record baseline behavior for portrait and landscape.
- Build a fixed sample set for long and irregular metadata strings.
- Document current truncation behavior and layout jumps during track switches.
- Audit every direct metadata text mutation path in `MainActivity` and `TrackTransitionChoreographer`.
- Confirm how the inline `MainActivity` text transition path relates to the choreographer path.
- Confirm whether any existing code depends on the three text views by identity rather than by content.
- Audit palette ownership boundaries for metadata text versus `statusText`.
- Audit animation token drift between `TrackTransitionDesignTokens` and the actual choreographer implementation.
- Decide whether `TrackTextSceneView` replaces the whole metadata container or becomes the container's only child.
- Decide and document the state strategy.
  Recommended default: `TrackState` keeps raw strings for persistence and restore.
  Measured scene objects are derived on demand from current bounds and are not stored inside `TrackState`.
- Decide the rollout mechanism for the new path.
  Preferred default: a runtime feature flag that keeps the old path available until Milestones 3-5 are complete.

### Deliverables

- short baseline notes added to the PR description or engineering notes,
- a reusable sample metadata list for manual verification,
- an inventory of all text mutation entry points,
- a decision record for container strategy and `TrackState` versus scene ownership,
- a cutover and rollback decision for the feature flag.

### Suggested commit

`docs: capture baseline scenarios for track text migration`

### Files

- no required code files,
- optional note appended to this document or PR notes only.

### Acceptance criteria

- Everyone working on the feature can validate the same sample cases.
- There is a shared definition of success for long-title behavior.
- All active metadata text mutation paths are enumerated before code changes begin.
- The team agrees which path is authoritative during the migration.
- The team agrees on container strategy and scene/state ownership before `TrackTextSceneView` is introduced.

### Baseline audit notes (2026-03-29)

Sample metadata set for manual checks:

- `Short title` / `Short artist` / `Short album`
- `A very long track title that keeps going through multiple clauses and still needs to fit before truncation kicks in` / `Single artist` / `Concise album`
- `Title With MIXED Caps, Numbers 1997, and / separators` / `Artist Name feat. Guest One, Guest Two` / `Album Title Deluxe Edition`
- `电子乐测试标题：带有全角字符与英文 Mixed Layout` / `双语 Artist Name` / ``
- `Track title` / `Artist` / `An album subtitle that is nearly as long as the artist line`

Current metadata mutation inventory:

- `MainActivity.renderState()` writes `statusText`, `trackText`, `artistText`, and `albumText`.
- `MainActivity.updateTrackInfo()` writes `trackText`, `artistText`, and `albumText`, and `applyTrackBinding()` routes through it.
- `TrackTransitionChoreographer.animateTrackTextTransition()` mutates `view.text` mid-animation for each metadata field.
- `MainActivity.animateTrackTextTransition()` contains a second inline fallback path that also mutates `view.text` mid-animation when the choreographer path is unavailable.
- `saveUIState()` still reads back from live `TextView` instances, so restore currently depends on view state rather than a derived scene.

Decisions captured for migration:

- Container strategy: keep the existing portrait and landscape `textContainer` views, and make `TrackTextSceneView` their only child when the new renderer is enabled.
- State strategy: `TrackState` remains the owner of raw title/artist/album/status strings plus album art references; measured scene objects stay derived from current bounds and are not persisted inside `TrackState`.
- Authoritative transition path during migration: `TrackTransitionChoreographer` is the primary runtime path when initialized; the inline `MainActivity` text transition path is fallback-only and should be gated away for the new scene renderer.
- Rollout mechanism: add a runtime feature flag, default `false`, so the legacy `TextView` renderer stays available until palette and transition migration are complete.
- Final lifecycle outcome: the runtime feature flag was used during rollout, then removed together with the legacy renderer in Milestone 5 cleanup once scene rendering became the only supported path.
- Palette ownership boundary: `statusText` must be separated from metadata palette updates before shadow-mode measurement starts, because the current `PaletteManager.updateTextColors(mainLayout, ...)` recursive traversal recolors every `TextView` under `mainLayout`, including the status overlay.
- Token drift to fix in later milestones: `TrackTransitionDesignTokens.TextTransition` and `TrackTransitionChoreographer.animateTrackTextTransition()` currently diverge in durations, offsets, and interpolator usage.

## Milestone 1: Policy and Immutable Models

### Goal

Move text layout decisions out of `TextView` configuration and into explicit data structures.

### Status

Completed in code on 2026-03-29.

### Commit 1

`feat(text): add layout policy and immutable scene models`

Files to add:

- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicy.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutModels.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextScene.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextPalette.kt`

Files to add tests for:

- `app/src/test/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicyTest.kt`

Task checklist:

- Define field identifiers for title, artist, album.
- Define a policy input model that includes screen metrics from `ScreenAdapter`.
  Required inputs should include width, height, density, orientation, and available text bounds.
- Define `TrackTextStyleSpec` for font size, alpha, letter spacing, max lines, alignment.
- Define `TrackTextBlockSpec` for one logical field of text.
- Define `TrackTextLineLayout` for measured line metrics.
- Define `TrackTextBlockLayout` for one measured block.
- Define `TrackTextScene` as immutable render input.
- Define `TrackTextPalette` for primary, secondary, caption, background, shadow colors.
- Implement `TrackTextLayoutPolicy` as pure Kotlin.
- Encode fallback order explicitly.
  Title should resist shrinking first.
  Album should yield earliest.
- Make orientation and available bounds explicit inputs.
- Unify the current double-applied alignment behavior.
  The policy must account for both initial creation-time alignment and orientation-time reapplication currently split across `createTextViews()` and `updateTextViewProperties()`.

Test checklist:

- Short metadata should preserve nominal sizes.
- Long title should reduce within allowed limits before truncating.
- Artist and album should yield according to priority rules.
- Empty album should not reserve redundant visual space.
- Center and start alignment should be chosen by orientation policy, not by view mutation.

Acceptance criteria:

- No Android text classes are referenced by the policy.
- The policy can be tested on the JVM.
- The policy output is sufficient for a rendering engine to operate without reading `TextView` state.
- The policy can reproduce the same responsive sizing intent currently derived from `ScreenAdapter`.
- Alignment remains correct after orientation changes, not only on first view creation.

## Milestone 2: Android Measurement Engine in Shadow Mode

### Goal

Introduce the Android-specific measurement engine while keeping the visible UI on the current `TextView` path.

### Status

Completed in code on 2026-03-29.
The measurement engine and scene-preparation path landed; the temporary rollout stages described below are historical only.

### Commit 2

`feat(text): add Android track text layout engine and runtime flag`

Files to add:

- `app/src/main/java/com/example/roonplayer/ui/text/AndroidTrackTextLayoutEngine.kt`

Files to modify:

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`
- `app/src/main/java/com/example/roonplayer/config/AppRuntimeConfig.kt`
- `app/src/main/java/com/example/roonplayer/PaletteManager.kt`

Task checklist:

- Add a dedicated feature flag for scene-based metadata text rendering.
- Define a narrow engine API such as `measure(sceneSpec, bounds, density): TrackTextScene`.
- Use `TextPaint` and `StaticLayout` internally.
- Build per-field layout results from policy output.
- Preserve ellipsis behavior through the measured layout rather than live view config.
- Return line metrics needed for later shrink-wrap backgrounds.
- Keep Android shaping inside the engine only.
- Wire the engine into `MainActivity` as a non-rendering observer first.
- Ensure the new rendering path can remain disabled while the engine runs in shadow mode.
- Stop routing metadata-related palette updates through the full `mainLayout`.
- Make `statusText` color ownership explicit before any shadow-mode scene integration begins.
- Log or locally compare measured scene dimensions against existing text container bounds.

Implementation notes:

- Do not move measurement off the main thread in this commit.
- Do not change portrait/landscape layout composition yet.
- Do not remove any `TextView` code yet.
- Keep the feature flag defaulted to `false`.
- If commit atomicity matters, land the temporary `statusText` color isolation as a small adjacent commit immediately before or after the engine introduction rather than burying it inside a large mixed commit.

Acceptance criteria:

- The app still renders with current `TextView`s.
- `MainActivity` can build a measured scene for the current metadata.
- Long-title sample cases can be measured without touching `TextView` state.
- A runtime rollback switch exists before any visible cutover begins.

### Commit 3

`refactor(text): centralize metadata-to-scene preparation in MainActivity`

Files to modify:

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

Task checklist:

- Add a single helper that converts current playback metadata into text scene input.
- Stop scattering title/artist/album presentation rules across multiple methods.
- Prepare one stable source of truth for the upcoming custom view.
- Ensure both the current `TextView` path and future scene path can read from the same prepared state.
- Keep `TrackState` as raw string state for persistence and restore.
- Re-measure scene output from `TrackState` and current bounds instead of caching measured scene objects inside `TrackState`.
- Implement explicit shadow-mode comparison logging.
  Compare engine scene height against the currently visible metadata layout height.
  Emit warning logs when height deviation exceeds 2dp or line-count deviation exceeds 1.
  Recommended exit gate: at least 90 percent of baseline samples stay within those thresholds.

Acceptance criteria:

- Text content preparation is not duplicated in update and transition code.
- There is a clear seam where rendering strategy can switch from `TextView` to scene view.
- Shadow mode has an explicit exit gate rather than an open-ended observation phase.
- The comparison mechanism is concrete enough to surface outliers without relying only on human observation.
- The raw state versus measured scene ownership boundary is explicit.

## Milestone 3: Stable Scene Rendering

### Goal

Render track metadata through one custom view while preserving the rest of the screen layout.

### Status

Completed in code on 2026-03-29.

### Commit 4

`feat(text): add TrackTextSceneView for stable scene rendering`

Files to add:

- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextSceneView.kt`

Files to modify:

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

Task checklist:

- Implement a custom view that can draw a stable `TrackTextScene`.
- Support separate block rendering for title, artist, album.
- Draw text using engine output rather than re-deciding wrapping in `onDraw`.
- Support container alignment for portrait and landscape.
- Make the container decision explicit.
  Preferred default: keep the existing portrait and landscape `textContainer` views and use `TrackTextSceneView` as their only child to minimize layout churn.
- Expose explicit APIs.
  `setScene(scene)`
  `setPalette(palette)`
  `clearScene()`
- Keep the view stateless outside its current scene and palette.
- Provide a debug option to render block bounds and line bounds if useful.
- Keep the new view dark until enabled by the runtime feature flag.

Acceptance criteria:

- One `TrackTextSceneView` can visually replace the three metadata `TextView`s in a static state.
- No transition animation behavior changes yet.
- The screen layout remains correct in both orientations.
- The feature-flag-off path still renders through legacy metadata `TextView`s.
- The chosen container strategy is implemented consistently in both portrait and landscape layouts.

### Commit 5

`refactor(layout): add TrackTextSceneView layout path behind feature flag`

Files to modify:

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`
- `app/src/main/java/com/example/roonplayer/LayoutOrchestrator.kt`

Task checklist:

- Wire portrait and landscape layout composition to support both renderers.
- When the new feature flag is on, render metadata with `TrackTextSceneView`.
- When the new feature flag is off, keep the legacy metadata `TextView` path intact for rollback.
- Keep `statusText` untouched for now.
- Update `applyPortraitLayout()` to attach the chosen scene-view container path.
- Update `applyLandscapeLayout()` to attach the chosen scene-view container path.
- Keep `updateTextViewProperties()` intact for the feature-flag-off legacy path.
- For the feature-flag-on path, bypass `updateTextViewProperties()` or reduce it to shared scene-policy input only without deleting legacy behavior in this commit.
- Ensure layout container sizing passes correct bounds into the scene engine.
- Migrate `removeExistingViews()` and layout detach logic away from the old three-view assumption.
- Update `LayoutOrchestrator.Delegate.detachReusableViews()` to match the new reusable view set.
- Migrate `renderState()` so restore and orientation rebuild no longer assume direct writes into the three metadata `TextView`s.
- Do not delete legacy metadata `TextView` fields or creation helpers in this commit.

Release note:

- Commit 4 and Commit 5 must live behind the same runtime feature flag.
- This commit must not remove rollback capability when the feature flag is off.
- Preferred landing model: Commit 5 and Commit 6 ship in the same PR, with the flag enabled only after both are verified.

Acceptance criteria:

- With the feature flag on, metadata is rendered by `TrackTextSceneView`.
- With the feature flag off, the legacy metadata `TextView` path still works.
- Static screen rendering works in portrait and landscape.
- Orientation changes on the feature-flag-on path do not rely on legacy metadata `TextView` instances.
- State restore no longer crashes or silently bypasses rendering when the scene path is enabled.

## Milestone 4: Scene-Based Transition Animation

### Goal

Make transitions animate between two measured scenes instead of mutating live text views.

### Status

Completed in code on 2026-03-29.

### Commit 6

`refactor(transition): route all text transitions through scene interpolation`

Files to modify:

- `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionChoreographer.kt`
- `app/src/main/java/com/example/roonplayer/MainActivity.kt`
- `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionDesignTokens.kt`

Files to add tests for:

- `app/src/test/java/com/example/roonplayer/ui/text/TrackTextSceneTransitionStateTest.kt`

Task checklist:

- On the feature-flag-on path, remove direct dependency on metadata `TextView` references from the choreographer.
- Replace scene-path `resolveTextViewForField()` and direct `view.text = ...` behavior with scene handoff.
- Preserve one authoritative legacy `TextView`-backed text animation path for the feature-flag-off rollback path until Commit 9 cleanup.
- Remove or fully gate the duplicate inline metadata text-animation path inside `MainActivity` for the enabled scene path.
- Let the choreographer animate transition progress only.
- Eliminate hardcoded animation values that drift from `TrackTransitionDesignTokens`.
  Scene transition timing, stagger, and shift distance should read from design tokens.
  Replace raw pixel offsets with dp-based token conversion.
- Migrate or delete `activeTextFieldAnimators`, `registerTextAnimator()`, and `cancelActiveTextAnimators()` for the enabled scene path.
- Provide APIs for:
  setting source scene,
  setting target scene,
  updating progress,
  finishing or cancelling transition.
- Define and implement completion semantics explicitly.
  The scene transition completion callback must fire exactly once after the effective final text transition finishes.
- Ensure rollback still resolves cleanly.
- Ensure optimistic state and committed state continue to map correctly to rendered scenes.
- If the feature flag is off, legacy text transitions must continue to animate rather than silently degrading to static text swaps.
- If Commit 6 lands before Commit 7, gate legacy metadata writes so the enabled scene path does not keep updating bypassed `TextView`s.

Acceptance criteria:

- No text content is mutated during the animation itself.
- Track switch animation can run using old-scene/new-scene interpolation.
- Long-title transitions do not jump because of late relayout.
- There is no second active metadata text-animation implementation on the enabled rendering path.
- The feature-flag-off rollback path still supports animated metadata text transitions until cleanup intentionally removes the old path.
- Animator lifecycle cleanup remains correct after cancellations, rollbacks, and rapid skips.
- Transition completion callbacks are triggered accurately and exactly once.

### Commit 7

`refactor(transition): align track binding and commit flow with scene rendering`

Files to modify:

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

Task checklist:

- Update `applyTrackBinding()` to set the active scene instead of updating three text views.
- Update `commitTrackStateOnly()` to commit scene-ready state only.
- Verify that `renderState()` introduced in Commit 5 coordinates correctly with the new binding and commit flow.
- Keep `TrackState` data valid for persistence and resume.
- Ensure hydration from committed playback snapshot can rebuild a stable scene.
- Preferred landing model: Commit 6 and Commit 7 merge together, or Commit 6 must leave no semantically active dead writes on the enabled path.

Acceptance criteria:

- Stable rendering and transition rendering consume the same prepared scene data.
- Track commit logic no longer assumes the presence of three text views.
- The enabled scene path no longer relies on `updateTrackInfo()` mutating legacy metadata `TextView`s.
- Restored state and live-updated state follow the same scene preparation path.

## Milestone 5: Palette Migration and Cleanup

### Goal

Remove the remaining implicit coupling to `TextView` traversal and finish the migration.

### Status

Completed in code on 2026-03-29.

### Commit 8

`refactor(palette): replace TextView traversal with explicit text palette`

Files to modify:

- `app/src/main/java/com/example/roonplayer/PaletteManager.kt`
- `app/src/main/java/com/example/roonplayer/MainActivity.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextSceneView.kt`

Task checklist:

- [x] Change `PaletteManager` to return a palette object rather than recursively mutating `TextView` colors.
- [x] Apply palette updates directly to `TrackTextSceneView`.
- [x] Preserve contrast logic and color selection behavior.
- [x] Keep album art background updates independent from metadata text rendering.
- [x] Define explicit ownership for `statusText` color so metadata palette updates cannot accidentally overwrite it.
- [x] Add palette dirty checking so unchanged palette values do not trigger redundant invalidation during animated background updates.
- [x] Remove any temporary status-text isolation shim introduced during Milestone 2 in favor of the final explicit palette model.

Acceptance criteria:

- [x] Text color changes no longer depend on scanning the view tree.
- [x] `TrackTextSceneView` fully owns metadata text paint colors.
- [x] `statusText` remains on an explicit and independently controlled color path.
- [x] Palette animation does not force unnecessary redraw work when effective palette values are unchanged.

### Commit 9

`chore(cleanup): remove obsolete metadata TextView code paths`

Files to modify:

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`
- `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionChoreographer.kt`
- `app/src/main/java/com/example/roonplayer/PaletteManager.kt`

Task checklist:

- [x] Delete dead metadata `TextView` fields and helper methods.
- [x] Delete unused update helpers that only existed for the old text path.
- [x] Remove the feature flag and its gate branches only when the legacy rollback path is intentionally retired together with old metadata rendering.
- [x] Decide explicitly not to defer cleanup: the rollback path is intentionally retired together with the old metadata renderer.
- [x] Remove stale imports and null-guard branches related to the removed views.
- [x] Re-run unit tests.

Acceptance criteria:

- [x] There is a single rendering path for metadata text.
- [x] No dead compatibility layer remains for removed metadata `TextView`s.
- [x] The feature flag lifecycle is resolved explicitly rather than left pointing to a removed path.

## Milestone 6: Premium Visual Layer

### Goal

Add the visual advantages that motivated the migration in the first place.

### Status

Partially completed in code on 2026-03-29.
Scene-only rendering, shadow treatment, and token alignment landed; compact text-background shapes, differentiated block treatments, and physical-device palette QA remain open.

### Commit 10

`feat(text): add shrink-wrap backgrounds and premium scene polish`

Files to modify:

- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextSceneView.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/AndroidTrackTextLayoutEngine.kt`
- `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionDesignTokens.kt`

Task checklist:

- [ ] Use per-line metrics to build compact background shapes.
- [ ] Support different visual treatment for title vs supporting lines.
- [x] Add subtle shadow or glass treatment if it does not reduce legibility.
- [x] Tune scene transition offsets and alpha curves for the custom text layer.
- [ ] Validate readability on dark, bright, and low-contrast album art palettes on physical devices.

Acceptance criteria:

- [ ] Backgrounds hug text content instead of using one blunt rectangle.
- [x] Visual polish is implemented on top of a stable architecture, not mixed into layout logic.

## Per-File Work Breakdown

## `app/src/main/java/com/example/roonplayer/MainActivity.kt`

Responsibilities:

- own scene input preparation,
- own orientation-aware bounds handoff,
- own view composition,
- stop owning text layout policy details.

Required changes:

- introduce fields for the engine and scene view,
- route metadata updates through scene preparation,
- replace old metadata `TextView` creation and binding,
- adapt track transition hooks to scene-based APIs,
- apply palette updates explicitly to the scene view.
- keep `TrackState` as raw string state and re-measure scenes from current bounds when rendering or restoring state.

Remove by the end:

- three metadata `TextView` fields,
- `updateTextViewProperties()` as a view-mutation function,
- any direct metadata `TextView.text` assignment.

## `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionChoreographer.kt`

Responsibilities:

- animate progress and numeric properties,
- never own layout or text measurement decisions.

Required changes:

- remove metadata `TextView` constructor parameters,
- add scene transition APIs,
- preserve existing track skip gesture behavior,
- preserve rollback semantics and session safety.

Remove by the end:

- `resolveTextViewForField()`,
- direct metadata text mutation inside animation callbacks.

## `app/src/main/java/com/example/roonplayer/PaletteManager.kt`

Responsibilities:

- compute color choices,
- expose palette values.

Required changes:

- stop mutating `TextView` nodes,
- return explicit palette data,
- keep contrast and background logic reusable.

## `app/src/main/java/com/example/roonplayer/LayoutOrchestrator.kt`

Responsibilities:

- remain thin,
- trigger orientation rebuilds without encoding metadata rendering assumptions.

Required changes:

- ensure reusable metadata scene view is detached and reattached correctly,
- avoid assumptions about three distinct metadata child views,
- coordinate the migration of `applyPortraitLayout()`, `applyLandscapeLayout()`, and `removeExistingViews()` with the chosen container strategy.

## `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicy.kt`

Responsibilities:

- pure layout fallback rules,
- orientation and bounds aware defaults,
- no Android dependencies.

Tests required:

- long title fallback,
- album deprioritization,
- empty-field compaction,
- orientation alignment selection.

## `app/src/main/java/com/example/roonplayer/ui/text/AndroidTrackTextLayoutEngine.kt`

Responsibilities:

- transform style specs into measured scene output,
- preserve line metrics,
- avoid hidden side effects.

Tests or validation required:

- manual sample validation against baseline cases,
- debug logging or visual overlays during integration.

## `app/src/main/java/com/example/roonplayer/ui/text/TrackTextSceneView.kt`

Responsibilities:

- draw stable scene,
- interpolate between scenes,
- consume palette explicitly.

Validation required:

- portrait and landscape layout correctness,
- stable rendering of empty album / long title cases,
- no visible relayout jumps during transitions.

## Execution Checklist

### Before coding

- [x] Freeze sample metadata cases.
- [x] Confirm preferred document of record for baseline notes.
- [x] Add and document a runtime feature flag for scene-based metadata text.
- [x] Decide the threshold logging format for shadow mode before enabling the new path anywhere.

### During migration

- [x] Policy stays JVM-testable.
- [x] Android text measurement remains isolated to one package.
- [x] New scene view does not re-derive layout rules in `onDraw`.
- [x] Transition code animates progress only.
- [x] Palette no longer scans the view hierarchy for metadata text.
- [x] `MainActivity` no longer contains a shadow second implementation of active text animation for the enabled path.
- [x] The feature flag and rollback path were intentionally removed during cleanup rather than left as dead compatibility branches.

### Before merge

- [x] `./gradlew test` passes.
- [x] Portrait and landscape layout paths both compile and render through the same scene-only metadata pipeline.
- [x] Long-title and mixed-language metadata are covered by automated policy tests; physical-device readability QA is still recommended.
- [x] Rollback and rapid skip scene-state behavior is covered by automated transition-state tests; device-level interaction QA is still recommended.
- [x] Shadow mode scaffolding was removed after the cutover; there is no longer a second renderer to compare against.
- [x] No dead metadata `TextView` path remains.
- [x] The feature flag lifecycle is closed: the old path was intentionally removed together with cleanup.

## Suggested Commit Order Summary

1. `feat(text): add layout policy and immutable scene models`
2. `feat(text): add Android track text layout engine and runtime flag`
3. `refactor(text): centralize metadata-to-scene preparation in MainActivity`
4. `feat(text): add TrackTextSceneView for stable scene rendering`
5. `refactor(layout): add TrackTextSceneView layout path behind feature flag`
6. `refactor(transition): route all text transitions through scene interpolation`
7. `refactor(transition): align track binding and commit flow with scene rendering`
8. `refactor(palette): replace TextView traversal with explicit text palette`
9. `chore(cleanup): remove obsolete metadata TextView code paths`
10. `feat(text): add shrink-wrap backgrounds and premium scene polish`

## Recommended Definition of Done

The migration is done only when all of the following are true:

- metadata text rendering no longer depends on the three old metadata `TextView`s,
- layout decisions are produced before rendering,
- transition animation no longer mutates text content mid-flight,
- there is only one active metadata text-animation implementation,
- palette application is explicit,
- metadata palette updates cannot accidentally restyle `statusText`,
- long-title behavior is deterministic,
- the new rendering path is the only active metadata path,
- the feature flag lifecycle has been resolved explicitly.
  If the old path has been deleted, the flag and its dead branches are gone too.
  If rollback is still required, cleanup of the old path has been deferred on purpose.

## Additional Test Expectations

In addition to `TrackTextLayoutPolicy` JVM tests, the migration should include pure Kotlin tests for transition-facing scene state wherever possible.

Minimum recommended coverage:

- source scene to target scene progression,
- cancellation before completion,
- rollback after optimistic transition,
- repeated target replacement during rapid skips,
- progress reset after commit or cancellation,
- transition completion callback fires exactly once,
- restore from raw `TrackState` produces a scene consistent with policy output for the current orientation and bounds.

Preferred implementation:

- keep the transition bookkeeping in a pure Kotlin helper or model layer where it can be tested without Android rendering dependencies.
