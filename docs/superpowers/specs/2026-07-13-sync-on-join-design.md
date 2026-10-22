# Skein "sync-on-join" — design

**Date:** 2026-07-13
**Status:** approved (design), pending implementation plan
**Supersedes (partially):** the play-phase, control-channel-driven sync trigger from the
loader-adapter work. See "Relationship to existing handshake code" below.

## Problem

Skein currently triggers a client sync from `ClientPlayerNetworkEvent.LoggingIn`
(`SkeinClient.onClientLoggingIn`), which fires in Minecraft's **PLAY phase** — after the
Forge login handshake completes. But when a client is missing mods the server has
(the primary modpack-sync use case), Forge terminates the connection during the
**LOGIN phase** with "mismatched mod channel list", *before* the PLAY phase. Skein's
sync therefore never runs.

Evidence (real logs, 2026-07-13, joining a modded server missing `chipped` +
`resourcefullib` on the client):

- Server: `Channels [chipped:main] rejected their client side version number` →
  `Terminating connection with client, mismatched mod list` →
  `lost connection: Connection closed - mismatched mod channel list`
- Client: `The server is likely to require channel [[chipped:main]] to be present, yet
  we don't have it` → `Server has additional mods that may be needed on the client:
  resourcefullib@2.1.29, chipped@3.0.7`

Because mods like `chipped` register network channels that must exist at game-load
time, a mid-session download can never satisfy the handshake — **a game restart after
downloading is mandatory**, and the sync must not depend on completing the MC login.

A separate, already-fixed defect made the earlier symptom destructive: the default
diff policy was `QUARANTINE`, so on an empty/partial manifest the client deleted the
player's own mods. That is now `additiveOnly()` in `SyncComponents.forSync` (never
deletes files the player added). This spec does not change that.

> **Superseded:** `docs/superpowers/specs/2026-08-09-install-ledger-design.md` later
> replaced `additiveOnly()` in `SyncComponents.forSync` with the ledger-gated `safe()` —
> a local extra file is now quarantine-eligible only if it's a member of the persisted
> install ledger (paths Skein itself installed in a previous sync), which still never
> touches a player-added file. The paragraph above describes this spec's original,
> now-superseded state.

## Goals

- Player presses **Join** as normal; if the join is rejected for missing mods, Skein
  detects it, downloads the pack over HTTP, and prompts a restart. On the next join the
  client has the mods and the handshake passes.
- Sync runs over **HTTP only**, independent of the Minecraft connection.
- **Zero-config happy path**: discover the Skein HTTP endpoint by convention from the
  server address the player just tried. Fall back to a manual URL field when discovery
  fails.
- Keep the destructive-delete fix (additive sync).

## Non-goals

- Auto-relaunch of the JVM (v1 asks the player to restart manually; the existing
  `ClientRelauncher.canRelaunch()==false` contract stands).
- Web admin dashboard changes (explicitly out of scope for this work).
- Solving auth-token discovery automatically (token, when set, is entered in the manual
  fallback field).

## User flow

```
Player clicks Join on a server
      │
      ▼
Forge login handshake ──pass──► enter game normally (mods already complete)
      │
   rejected: "mismatched mod list"
      │
      ▼
Skein intercepts the DisconnectedScreen (mod-mismatch only)
      │  knows the address just attempted, e.g. 1.2.3.4:25565
      ▼
derive Skein URL = http://1.2.3.4:25585  ──► probe GET /manifest/root
      │                                            │
   found (200 + decodable manifest)             not found (404/401/timeout/etc.)
      │                                            │
      ▼                                            ▼
run HTTP sync (download pack)              show a field to enter URL (+ token) → sync
      │
      ▼
done → "Downloaded updates — please restart your game" → player restarts → rejoins → passes
```

## Architecture: shared vs loader split

Loader modules (`skein-forge`, `skein-neoforge`) cannot be compiled or tested in this
environment (no NeoGradle/ForgeGradle toolchain + MC mappings). Therefore **all logic
lives in shared modules that are unit-testable here**; the loader keeps only a thin GUI
bridge, written but marked `UNVERIFIED` and verified on the toolchain machine.

### Shared components (skein-bootstrap / skein-app — testable now)

| Component | Responsibility | Test |
|-----------|----------------|------|
| `SkeinEndpoint.deriveFrom(mcAddress, skeinPort)` | Parse a Minecraft server address (`host`, `host:port`, IPv6 `[..]:port`) and produce the candidate Skein base URL `http://<host>:<skeinPort>`. Default `skeinPort` = 25585 (matches `SkeinServerConfig` default). Pure function. | pure unit tests |
| `EndpointProbe.probe(baseUrl, token)` | `GET <baseUrl>/manifest/root`; return `found` iff HTTP 200 and the body decodes as a root manifest (`JsonManifestCodec.decodeRoot`). Reuses the existing `HttpRangeClient` / `HttpManifestResolver`. | localhost `com.sun.net.httpserver.HttpServer` |
| `SkeinClientSession` (refactor) | Extract the sync body so it can be driven by `(baseUrl, token)` directly, not only by a `SessionGrantS2C`. The existing `run(SessionGrantS2C)` becomes a thin adapter over the new `run(baseUrl, token, expectedRootHash?)`. The root-hash fast-path still works: when `expectedRootHash` is absent it fetches `/manifest/root` and diffs unconditionally. | extend existing `SkeinClientSessionTest` (localhost server) |

`SkeinEndpoint` and `EndpointProbe` are new, single-purpose types. The
`SkeinClientSession` refactor keeps its public "never throws, returns `SyncOutcome`"
contract.

### Loader components (skein-forge / skein-neoforge — UNVERIFIED, toolchain-verified)

| Component | Responsibility |
|-----------|----------------|
| `SkeinDisconnectHook` | Subscribe to the client screen-init event; when the shown screen is Minecraft's disconnect screen for a **mod-mismatch** reason, read the address the player last attempted (from the client's last server-connection state), open `SkeinProgressScreen`, and run discovery. On successful auto-discovery the sync **starts automatically** (no extra click); on discovery failure the screen shows the manual URL field with a Sync button. Mirror the file in both loaders (only the loader-API package prefix differs). |
| `SkeinProgressScreen` (extend) | Add a manual URL (+ optional token) text field, shown only when auto-discovery fails; keep the existing progress bar, terminal states, and restart/Quit button. |

The loader hook does not make sync decisions — it derives/probes via the shared
components, then runs the shared session on the existing background `skein-sync`
executor, and reflects `SyncEvent`s in the screen exactly as today.

## Error handling / edge cases

- **Token-protected server:** an unauthenticated probe returns 401 → treated as "not
  found" → manual fallback where the player enters URL + token. (v1: the no-token case —
  the server template's documented default for private/whitelisted servers — is the
  zero-config happy path.)
- **Discovered but download fails** (network drop, hash mismatch): the existing
  retry/backoff (`DefaultRetryPolicy` + `ExponentialBackoff`) applies; a terminal
  failure surfaces as `SyncOutcome.Failed` with its `ErrorCode` on screen (existing
  behavior).
- **Download succeeds:** v1 cannot relaunch → show "please restart your game" + Quit
  button (existing `SkeinProgressScreen` terminal UI for `RestartRequired`).
- **Player-added mods** not in the manifest: never deleted (additive policy — already
  fixed).
- **Disconnect for a non-mod-mismatch reason:** Skein does not interfere; the normal
  disconnect screen is shown.

## Testing

Runnable in this environment (JUnit + localhost `HttpServer`, the established pattern):

- `SkeinEndpointTest` — `host`, `host:port`, custom Skein port, hostname vs IPv4, IPv6
  literal, malformed input.
- `EndpointProbeTest` — 200 + valid manifest ⇒ found; 404 / 401 / connection-refused /
  timeout / non-manifest body ⇒ not found.
- `SkeinClientSessionTest` (extended) — full sync driven by `(baseUrl, token)` against a
  localhost server; asserts additive behavior (a player-only mod survives) and the
  restart-required outcome when a mod is fetched.

Verified only on the toolchain machine (documented as manual steps in the plan):

- `:skein-forge:build` / `:skein-neoforge:build` compile the new loader classes.
- `runClient`: join a server missing a mod → disconnect intercepted → auto-discovery →
  download → restart → rejoin succeeds; and the manual-URL fallback path.

## Relationship to existing handshake code

The play-phase path — `ForgeControlChannel`/`NeoForgeControlChannel`, `SkeinNetwork`,
`HandshakeReplyC2S`/`SessionGrantS2C`, `ServerBootstrap`/`SkeinServerSession`,
`ClientBootstrap.install()` — is **no longer on the critical path** for triggering a
sync. The server side now only needs `SkeinHttpServer` running.

Decision for this scope: **keep the existing handshake code in place (do not delete
it)** to avoid scope creep and preserve the option of a future login-phase endpoint
handshake; simply stop depending on it for the sync trigger. Removal/cleanup can be a
later, separate change.
