# Skein loader adapter — design (NeoForge first)

Date: 2026-07-05
Status: approved for planning
Scope: complete the Minecraft glue so the finished shared engine actually runs
in-game. NeoForge 1.20.1 first; Forge is a later port of the same shape.

## Goal

Player joins a modded server → Skein diffs their instance against the server's
manifest → downloads only what changed (SHA-256, resumable) → new/updated mods
land in `mods/`, configs in `config/`, etc. → because mods only load at JVM
start, the client stages the changes and asks the player to restart.

The shared engine (core, transfer, storage, server, app) is done and verified.
The only missing piece is the loader glue. This design keeps that glue **thin**
and lifts every loader-agnostic decision into a new, unit-tested shared module.

## Hard environment constraint

This workstation **cannot build the loader modules** — they need NeoGradle +
Minecraft mappings from the network. Therefore:

- Everything in `skein-bootstrap` (below) is pure Java and IS verified here
  (`javac` + JUnit), same as the rest of the shared code.
- `skein-neoforge` touches `net.minecraft.*` / NeoForge APIs and is **written but
  NOT compiled here**. Every such file is marked `// UNVERIFIED: needs NeoGradle
  toolchain`. It is verified on the user's build machine with
  `./gradlew :skein-neoforge:build` and a real client/server run.

We never claim the loader code "works" from this environment — only that its
logic is delegated to code that is tested here.

## Approach: `skein-bootstrap` shrinks the unverifiable surface

Chosen over putting the composition root directly in `skein-neoforge`, because it
moves the handshake state machine and orchestrator wiring out of the loader
module (where it can't be tested) into a shared module (where it can). The loader
adapter is left with only mechanical impls, network registration, and one GUI
screen. This matches the project's "~90% shared" rule.

## Module 1 — `skein-bootstrap` (new, pure Java, verified here)

Dependencies: `skein-platform`, `skein-core`, `skein-common`, `skein-app`,
`skein-transfer`, `skein-storage`, `skein-server`. NO `net.minecraft.*`. It is the
loader-agnostic **composition root + session state machines**.

### Types

- `ClientBootstrap`
  - Input: a `ClientPlatform` (the loader hands this in).
  - Registers a `ControlChannel.InboundHandler` that decodes `SessionGrantS2C`.
  - On grant: builds a `SkeinClientSession` and starts it.

- `SkeinClientSession` — the client state machine (the interesting, tested part)
  - Holds the composition root currently duplicated in `SkeinCli.sync`:
    `HttpManifestResolver`, `FileSystemLocalStateProvider`, `DefaultFileManager`,
    `DefaultFileDownloader` (retry/backoff), `DefaultDownloadService`,
    `DefaultApplyService`, `SyncOrchestrator`, `SynchronousEventBus`.
  - Paths come from `ClientPlatform.paths()` (gameDir/modsDir/configDir/skeinDir),
    never guessed. Target dir = `gameDir()`; staging/quarantine under `skeinDir()`.
  - Runs the orchestrator **off the game thread** via an injected
    `ExecutorProvider` (no `new Thread` in shared code).
  - Fast path: if `SessionGrantS2C.rootManifestHash()` equals the cached root
    hash, report `UP_TO_DATE` and skip download entirely.
  - Republishes `SyncEvent`s to a `SyncProgressListener` (state, diff totals,
    byte progress) that the loader's Screen subscribes to.
  - On terminal `SyncOutcome`, produces a `SessionResult`:
    `UpToDate | Applied(needsRestart) | Failed(SkeinException)`.

- `SkeinServerSession` + `ServerBootstrap` — server side
  - `ServerBootstrap.start(...)` boots the existing `SkeinHttpServer`
    (`Supplier<GeneratedPack>` + optional client token + optional `AdminSurface`)
    on a configured bind address, and registers a `ControlChannel` handler.
  - On inbound `HandshakeReplyC2S`: version/compat check, then reply
    `SessionGrantS2C(transferEndpoint, sessionToken, packVersion, rootManifestHash)`.
  - Token issuance: reuse the server's configured client token (v1). A per-session
    short-lived token is a later enhancement, out of scope.

- Small support types: `SyncProgressListener`, `SessionResult`,
  `BootstrapConfig` (bind host/port, token, category→dir map, timeouts) so nothing
  is hardcoded.

### Tests (run here)

- Fake `ControlChannel` (records sent bytes, lets tests inject inbound bytes) +
  fake `ClientPlatform`/`PlatformPaths` (tmp dirs) + a fake or localhost HTTP
  server (the existing `com.sun.net.httpserver` integration pattern).
- Cases: handshake round-trip (reply → grant); up-to-date fast path; full sync
  applies files to the right dirs and reports `needsRestart`; failure maps to
  `SessionResult.Failed` with the right `ErrorCode`.
- A standalone `javac` driver `main` mirroring the repo's quick-check convention.

## Module 2 — `skein-neoforge` (written, verified on build machine)

Dependencies added to `build.gradle.kts`: `neoforge:$neoforgeVersion` +
`:skein-bootstrap` (which transitively brings the rest). Uncomment the module in
`settings.gradle.kts` and the plugin block.

### Types (all `// UNVERIFIED: needs NeoGradle`)

- `SkeinMod` — `@Mod("skein")` entry. Detects side; on client wires
  `ClientBootstrap`, on dedicated server wires `ServerBootstrap`.
- `NeoForgePlatformPaths` — maps NeoForge/`FMLPaths` + `Minecraft` dirs to
  `gameDir/configDir/modsDir/skeinDir`.
- `NeoForgeGameThread` — `Minecraft.getInstance()::execute` (client);
  server uses the server executor.
- `NeoForgeLoaderInfo` — loader = NEOFORGE, MC version, Skein mod version from
  `ModList`, installed mods list.
- `NeoForgeRelauncher` — `canRelaunch()` = false in v1 (manual restart prompt);
  hook left for a future launcher-aware impl.
- `NeoForgeControlChannel` — implements `ControlChannel` over NeoForge's
  networking. Registers one channel/payload carrying a raw `byte[]`; encodes an
  opcode + payload so both `HandshakeReplyC2S` and `SessionGrantS2C` share it.
  Hops to the correct thread before invoking the shared handler.
- `SkeinProgressScreen` — a `Screen` subscribed to `SyncProgressListener`: shows
  state, per-file/byte progress, and on completion a "Downloaded N files —
  please restart your game" panel with a Quit/Continue button. On failure shows
  the `ErrorCode` message + retry/back.

### Server auto-start

On dedicated-server startup the mod reads pack config (pack dir, port, token,
admin password) from a Skein server config file under `configDir()`, boots
`SkeinHttpServer` + `AdminSurface` via `ServerBootstrap`. Single-player/LAN host
side is out of scope for v1.

### mods.toml

Already present and correct (modId `skein`, neoforge `[47,)`, MC `[1.20.1,1.21)`).

## Flow at join (chosen UX: stage + manual restart)

1. Client connects; server handshake asks for Skein presence.
2. Client replies `HandshakeReplyC2S`; server replies `SessionGrantS2C`.
3. `SkeinClientSession` compares grant root hash to cache. Equal → done.
4. Otherwise open `SkeinProgressScreen`, run orchestrator off-thread:
   manifest → diff → download (resumable, verified) → apply to staging → commit.
5. On success with changes: prompt the player to restart (auto-relaunch only if a
   future `ClientRelauncher` can). On failure: show typed error, allow retry.

## Out of scope (v1)

Per-session tokens; auto-relaunch; Fabric; single-player host serving; hot
mod-reload (impossible — mods load at JVM start).

## Verification plan

- Here: `skein-bootstrap` via `javac --release 17` + standalone driver, and JUnit
  in the Gradle build (fakes + localhost HTTP server).
- Build machine: `./gradlew :skein-neoforge:build`, then NeoGradle `runClient` /
  `runServer` against a real demo pack to confirm files land in `mods/`+`config/`
  and the restart prompt appears.
- Forge port (later): same `skein-bootstrap`, a parallel `skein-forge` adapter.
