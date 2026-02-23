# CoverArt Android Optimization Checklist

This checklist captures the agreed optimization items for cover display layout, view switching logic, and art wall rendering. Items are sorted by execution priority.

## Completion Status (Implemented)

- All items in this checklist have been implemented in code.
- Some architecture-heavy items were delivered in phased form to reduce regression risk:
  - `ArtWallManager` split (layout + snapshot/restore + rotation pools + timer ownership)
  - `CoverArtDisplayManager` split (album-art drawable flattening + swap animation/cancel)
  - `LayoutOrchestrator` split (layout-parameter application sequencing for orientation/layout rebuild)

## P0 (Fix First)

### 1. Move art wall initial image decode off the main thread
- Problem: entering art wall currently decodes ~15 bitmaps on the UI thread.
- Impact: visible jank / frame drops when switching into art wall mode.
- Goal: all file IO and bitmap decode run on background threads; main thread only binds bitmaps to `ImageView`.

### 2. Eliminate cross-thread UI access in art wall rotation
- Problem: art wall rotation reads `ImageView.tag` / `artWallImages` from a background coroutine.
- Impact: race conditions and unsafe UI-thread access.
- Goal: UI reads/writes stay on the main thread; background work only handles decode and file existence checks.

### 3. Replace delayed art wall switch Timer flow with main-thread scheduling
- Problem: `isPendingArtWallSwitch` is mutated across background timer callbacks and UI callbacks.
- Impact: race conditions between `handlePlaybackStopped()`, resume/cancel, and delayed execution.
- Goal: schedule/cancel/execute delayed art wall switch on the main thread (single-threaded state ownership).

### 4. Harden `loadCompressedImage()` sample-size calculation
- Problem: `inSampleSize` can become `0` when source image is smaller than target.
- Impact: invalid decode options / crash risk.
- Goal: clamp sample size to `>= 1` and handle decode-bounds failures safely.

### 5. Reduce fast-switch album-art transition flicker (P0/P1 boundary)
- Problem: rapid track changes can still show unstable transition start states.
- Impact: visible flash during cover changes.
- Goal: stabilize transition start drawable and avoid overlapping/stacked transitions.

## P1 (High Value Next)

### 6. Avoid reloading all art wall images on rotation
- Problem: orientation change rebuilds the wall and reloads images.
- Impact: slow rotation experience and unnecessary IO/decode.
- Goal: reuse current wall bitmaps/paths during layout rebuild, then refresh incrementally if needed.

### 7. Decode art wall images at actual cell size
- Problem: `loadCompressedImage()` defaults to `300x300` regardless of actual grid cell size.
- Impact: blurry large-screen output or wasted memory on smaller screens.
- Goal: pass dynamic `cellSize` to decoding and make cache keys size-aware.

### 8. Unify image-loading entry points
- Problem: initial load, rotation updates, and now-playing cover paths use different loading flows.
- Impact: duplicated logic and inconsistent threading/caching behavior.
- Goal: one shared image-loading API with consistent threading, sampling, and memory-cache behavior.

### 9. Normalize cache responsibilities before merging implementations
- Problem: cache layers use mixed keys (`hash`, `imageKey`, `filePath`) with overlapping purposes.
- Impact: consistency bugs and hard-to-debug cache misses/staleness.
- Goal: define cache responsibilities and lifecycles first, then consolidate implementation safely.

## P2 (Architecture and Maintainability)

### 10. Split `MainActivity` by responsibility (God-class reduction)
- Problem: `MainActivity` mixes layout, image loading, transitions, gestures, networking, and art wall logic.
- Impact: high regression risk and low maintainability.
- Goal: split in phases:
  - `ArtWallManager` (first)
  - `CoverArtDisplayManager`
  - `LayoutOrchestrator`

### 11. Refine gesture/transition orchestration boundaries
- Problem: handoff chain is long, but choreographer directly owning the store would over-couple UI animation to app state.
- Impact: hard-to-test animation/business interactions.
- Goal: keep `MainActivity` as coordinator for transport + store + preview side effects, but narrow the `ChoreographerDelegate` interface.

### 12. Remove dead/partial cache/state fields
- Problem: some cache/state fields appear underused or partially implemented.
- Impact: cognitive load and misleading future maintenance.
- Goal: delete or complete unused cache/state paths and keep logs aligned with actual behavior.

## P3 (Polish)

### 13. Smooth responsive font sizing (remove density step jumps)
- Problem: density thresholds create abrupt font jumps (e.g. `density > 3.0f -> 0.8f`).
- Impact: inconsistent typography across boundary devices.
- Goal: replace step function with smooth interpolation (e.g. lerp).

### 14. Clean up queue-position mutation code (`removeAll` -> direct queue slicing)
- Problem: current code is acceptable for small N, but intent is less clear.
- Impact: readability only (not a real perf bottleneck).
- Goal: use a clearer queue/slice operation.

## Recommended execution batches

1. P0 stability/performance fixes
2. P1 rotation + size-aware decode + loading-path unification
3. P2 architecture extraction (start with `ArtWallManager`)
4. P3 polish and cleanup
