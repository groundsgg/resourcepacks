# Plugin Resourcepacks Design

## Status

Approved direction for implementing automatic Grounds PackSet delivery through Velocity, with a
shared client that can later be reused by Minestom.

## Goal

Every player receives the configured, validated Grounds PackSet without a config-service or CDN
request on the login path. Operators choose the PackSet source and channel through `plugin-config`.
The default configuration is seeded automatically and points at `grounds-global/stable` on the
Grounds CDN.

## Repository and ownership boundaries

### `resourcepacks`

Add a published `resourcepacks-client` module beside `resourcepacks-contract`. It owns:

- strict validation of the runtime source configuration;
- construction of the channel URL;
- bounded HTTP reads of channel and manifest documents;
- ETag-based refresh;
- validation through `resourcepacks-contract`;
- an atomic, immutable current PackSet snapshot;
- persisted last-known-good channel and manifest bytes for restart resilience.

The module has no Velocity, Minestom, `plugin-config`, or server lifecycle dependency.

### `plugin-resourcepacks`

Create a new repository containing the Velocity integration. It owns:

- the `plugin-resourcepacks` Velocity plugin entry point;
- the typed `plugin-config` definition;
- lifecycle management for one shared `resourcepacks-client` instance;
- player login and resource-pack status listeners;
- sending the ordered pack stack to players;
- reconciling connected players when a new valid snapshot becomes active;
- metrics and operational logging.

`plugin-player` remains responsible only for player presence, sessions, and heartbeats.

### `grounds-minestom-runtime`

A later `runtime-resourcepacks` module will adapt the same `resourcepacks-client` snapshot to
Minestom. It is not part of the first Velocity delivery slice and will not duplicate resolver or
cache logic.

## Configuration contract

`plugin-resourcepacks` registers one typed document through `plugin-config`:

```text
app: network
env: <deployment environment>
namespace: resourcepacks
key: global
```

Version 1 payload:

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "source": {
    "baseUrl": "https://cdn.grounds.gg",
    "packSet": "grounds-global",
    "channel": "stable"
  },
  "required": true,
  "prompt": "Grounds benötigt seine Resourcepacks."
}
```

The `ConfigDefinition` uses this payload as its default. `plugin-config` seeds it through
`syncDefaults` only when the document is absent; it never overwrites an existing operator value.
Stage is changed once to `channel=edge`, while a fresh environment safely starts on `stable`.

The deployment environment is supplied separately to the plugin and selects the config scope. It
is not inferred from the PackSet channel.

## Runtime source validation

The client accepts configurable sources under these rules:

- `baseUrl` is an absolute HTTPS origin with no user info, query, or fragment;
- `packSet` and `channel` are single safe path segments with no encoded traversal;
- the channel URL is
  `{baseUrl}/resourcepacks/packsets/{packSet}/channels/{channel}.json`;
- the decoded channel document must repeat the configured PackSet and channel;
- the target manifest must match the channel reference hash and size;
- the decoded manifest must match the channel target and PackSet;
- every manifest artifact URL must use the configured origin and canonical PackSet layout;
- all size and digest checks are mandatory before a snapshot can become current.

An arbitrary HTTPS origin is an administrator capability. Until `service-config` gains
application-level admin authorization, write access remains limited by the existing private-network
deployment controls. Exposing config writes outside that boundary requires completing service-config
admin authentication first.

## Cache and refresh model

Two caches have separate responsibilities:

1. `plugin-config` owns typed policy state, persisted config snapshots, NATS change notifications,
   and HTTP/ETag reconciliation with `service-config`.
2. `resourcepacks-client` owns validated channel and manifest state from the configured CDN.

The PackSet client refreshes in the background on startup, periodically, and immediately after a
source-config change. Conditional requests use ETags when provided. HTTP bodies have strict byte and
time limits.

Only a completely validated candidate replaces the current snapshot. Refresh errors retain the
last-known-good snapshot and use bounded retry with jitter. The persisted snapshot is written
atomically and revalidated before use after restart. A source-config change does not retain a
last-known-good snapshot from a different source as if it belonged to the new source; it remains
available only as an explicitly reported degraded fallback until the new source validates.

Player login reads one immutable in-memory snapshot and performs no config-service or CDN I/O.

## Velocity behavior

The plugin declares a required Velocity dependency on `plugin-config`. At initialization it:

1. obtains `ConfigManager` from `plugin-config`;
2. registers the typed document in degraded startup mode;
3. starts one PackSet client using a plugin-owned cache directory;
4. subscribes to config changes;
5. registers login and resource-pack status listeners.

When enabled and a valid snapshot exists, the plugin sends the packs in manifest order using their
manifest UUID, URL, SHA-1, required flag, and configured prompt. Reusing the manifest UUID lets a
new revision replace the previous pack instead of accumulating duplicate stack entries.

When a different valid snapshot becomes current, the plugin reconciles all connected players once.
Identical snapshots do not cause a resend. Status events are logged with player, pack UUID, target,
and terminal status, without logging tokens or unrelated player data.

If no valid snapshot exists, the proxy remains available but no incomplete or unvalidated pack is
sent. This state is surfaced prominently through logs and health/metrics. A required-pack refusal is
handled through Velocity's normal required-pack semantics.

## Deployment changes

The first rollout includes:

- publishing `resourcepacks-client` from the `resourcepacks` release workflow;
- creating and releasing `plugin-resourcepacks`;
- adding `plugin-config` and `plugin-resourcepacks` to both Velocity proxy plugin lists;
- setting `CONFIG_SERVICE_URL`, `CONFIG_NATS_URL`, and the existing projected Grounds token for
  `plugin-config`;
- supplying the config-scope environment to `plugin-resourcepacks`;
- seeding the global document, then setting Stage to `edge`;
- verifying one fresh login, one cached login, one live channel change, one CDN outage, and one
  proxy restart from the persisted last-known-good snapshot.

## Non-goals for the first slice

- per-gamemode or per-server PackSet overlays;
- a Minestom adapter;
- arbitrary non-HTTPS sources;
- downloading ZIP contents into the proxy;
- editing or publishing PackSets from the runtime plugin;
- replacing `plugin-player` or moving player-presence behavior.

Gamemode overlays can later compose a global snapshot with an additional selector while preserving
the same resolver, validation, ordering, and cache boundaries.

## Test strategy

`resourcepacks-client` tests cover canonical URL construction, hostile source values, channel and
manifest binding, digest/size failures, ETag refresh, timeouts, response limits, concurrent refresh,
atomic snapshot replacement, persisted cache recovery, and source changes.

`plugin-resourcepacks` tests cover config default seeding, config changes, zero-I/O login dispatch,
pack order and UUIDs, required/prompt mapping, duplicate suppression, online-player reconciliation,
status handling, shutdown, and absence/degraded behavior.

Integration tests use local bounded HTTP fakes and a real Velocity API test harness where available.
Deployment contract tests bind both plugin lists, required environment variables, config scope, and
the Stage `edge` override.
