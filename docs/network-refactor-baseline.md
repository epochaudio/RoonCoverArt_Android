# Network Refactor Baseline (Phase 0)

- Date: 2026-02-20
- Project: `/Users/yiwen/work/CoverArtForAndroid`
- Scope: discovery, request routing, pairing, subscription, token persistence, zone management

## 1. Baseline Goals

1. Keep user-visible behavior stable during network-core refactor.
2. Introduce measurable metrics before protocol/session rewrites.
3. Ensure every new phase is rollout-safe and rollback-safe.

## 2. Structured Logging Fields

All network-critical logs should include these fields when applicable:

- `connection_id`: lifecycle id for one websocket session.
- `request_id`: MOO Request-Id.
- `subscription_key`: active subscription key.
- `zone_id`: target zone id.
- `core_id`: paired core id when known.

## 3. Baseline KPIs

- Discovery duration: `discover_start -> discovered_first_core`
- Registration latency: `connect_ok -> register_complete`
- Subscription recovery latency: `reconnect_start -> subscriptions_restored`
- Reconnect count per session
- Unknown `request_id` response count

## 4. Manual Regression Checklist

1. First install, first pairing.
2. Upgrade install with existing token and zone selection.
3. Core reconnect after Wi-Fi restart.
4. Zone switch, next/prev track, play/pause.
5. Cover update + cover wall update during queue changes.

## 5. Rollout Flags (Phase 8)

Flags are runtime-overridable via SharedPreferences key prefix `runtime_config.`:

- `feature_flags.new_sood_codec`
- `feature_flags.new_moo_router`
- `feature_flags.new_subscription_registry`
- `feature_flags.new_zone_store`
- `feature_flags.strict_moo_unknown_request_id_disconnect`

Recommended rollout:

1. Enable `new_sood_codec` only.
2. Enable `new_moo_router` on internal devices.
3. Enable subscription + zone store flags in staged batches.
4. Keep strict unknown request-id disconnect disabled until compatibility is confirmed.

## 6. Local Verification Commands

```bash
./gradlew testDebugUnitTest --no-daemon
```

Optional focused tests:

```bash
./gradlew testDebugUnitTest --tests '*SoodProtocolCodecTest' --no-daemon
./gradlew testDebugUnitTest --tests '*RuntimeConfigResolverTest' --no-daemon
```
