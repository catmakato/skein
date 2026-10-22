# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Skein** — a from-scratch Minecraft modpack synchronizer (mods, configs, resource/shader packs, datapacks). On join it diffs the client against the server's manifest and downloads only what changed, SHA-256-verified and resumable. First target: **Minecraft 1.20.1 / Java 17**, on **both Forge and NeoForge**. The design is original — do not copy AutoModpack or other projects. A detailed multi-part design doc backs every decision (referenced in code comments as "design doc, Part N").

## Architecture: multi-loader by construction

The whole point of the module layout is that **~90% of the code never touches `net.minecraft.*`**, so one shared codebase drives both loaders with no duplication (MultiLoader pattern, not a separate Forge project).

Dependency arrows point **inward only**; a shared module may never depend on a loader module:

```
skein-common ─ dependency-free utils + logging facade (root of the graph)
    ▲
skein-core ─── pure-Java domain + application logic (NO Minecraft, NO other module but common)
    ▲
skein-platform ─ the LOADER SEAM: interfaces each adapter implements + byte[] packet codecs
    ▲                                    ▲
skein-neoforge (NeoGradle)        skein-forge (ForgeGradle)   ← thin adapters, loader API only
```

**Hard rules (will be enforced by an ArchUnit test):**
- `skein-core` must never import `net.minecraft.*` and must depend on nothing but `skein-common`.
- Packets are serialized to `byte[]` via `skein-platform`'s `PacketWriter`/`PacketReader`/`PacketCodec` — **never** Minecraft's `FriendlyByteBuf` in shared code. Each loader adapter only wraps/unwraps that `byte[]`. This is what makes packets shared and unit-testable.
- Everything a loader provides (paths, threads, networking, relaunch) goes behind a `skein-platform` interface (`ClientPlatform` aggregates them). Adapters build one `ClientPlatform` impl and hand it to the shared bootstrap.

Design principles the code follows: immutable `record` value objects; construction-time validation (e.g. `RelativePath` rejects path-traversal, `FileHash` validates length/hex); ports for anything crossing a boundary or doing I/O; no `new Thread()`/`Executors` in core (injected `ExecutorProvider`); manual constructor DI at a single Composition Root (no Spring/Guice); no hardcoded versions (see `gradle/libs.versions.toml` and `gradle.properties`).

## Build & test

Gradle multi-module (Kotlin DSL), Java 17 toolchain (a JDK 21 toolchain builds it fine).

```bash
./gradlew build          # compile + test all shared modules (60 JUnit tests, all green)
./gradlew test           # run all tests
./gradlew :skein-core:test
./gradlew :skein-core:test --tests "com.pathongko.skein.core.domain.PackVersionTest"
./gradlew :skein-platform:test --tests "*PacketCodecTest.handshakeReplyRoundTrips"
```

The Gradle wrapper (8.9) is committed, so `./gradlew` works out of the box. The
build targets a Java 17 toolchain (auto-detected at `C:\Program Files\Java\jdk-17`);
Gradle itself runs fine on JDK 21. Test-scoped deps (JUnit 5, AssertJ) come from
Maven Central on first build.

Gotcha: AssertJ's `assertThat(path).startsWith(other)` calls `toRealPath()` and
therefore requires the path to exist on disk. For lexical path checks use the
`java.nio` method instead: `assertThat(path.startsWith(other)).isTrue()`.

**Fast verification without Gradle:** the shared modules have **zero external runtime dependencies** (even the HTTP client uses the JDK's `java.net.http`), so they compile directly with `javac --release 17`. Compile order: `skein-common` first, then `skein-core`, then `skein-platform` and `skein-transfer` (both depend on core/common; put the earlier modules' classes on `-cp`). This is the primary way changes to shared logic have been verified in this repo — compile the sources, then run a small standalone driver `main` that asserts the behaviour. Use it as a quick sanity check before/instead of a full Gradle run.

## Loader adapter modules

`skein-forge` **is** included in `settings.gradle.kts` and builds here: `./gradlew :skein-forge:build` reaches ForgeGradle's Maven repos, downloads Forge 47.3.0 + MC 1.20.1 official mappings on first run, and produces `skein-forge/build/libs/skein-forge-<version>.jar` (confirmed green in this environment — first run took ~2m19s, mostly mapping/AT download). Use that command to verify changes to loader-API code in `skein-forge` by actually compiling them; don't write that code from memory and claim it works untested.

`skein-neoforge` is still **commented out** (`include("skein-neoforge")` in `settings.gradle.kts`) and has not been verified buildable here — the NeoGradle toolchain/mappings download hasn't been exercised in this environment. Treat it the same as before: don't assume it builds, and don't write its loader-API code from memory without compiling it first (uncomment the include and try `./gradlew :skein-neoforge:build`, same as skein-forge, before trusting it).

## Status / roadmap

Done and verified (`javac`-verified with standalone drivers; JUnit tests also written for the Gradle build): build skeleton, domain model, hashing (`MessageDigestHashService`), the `skein-platform` loader seam + packet codecs, the manifest system + `DefaultDiffEngine` (manifest + `LocalIndex` → `SyncPlan`), the `skein-transfer` engine — retry/backoff/resume (`DefaultRetryPolicy`, `ExponentialBackoff` + injectable `Jitter`, `ResumePlan`) and the full `DefaultFileDownloader` (byte-range resume, mirror rotation, single-pass hash verify, behind the `HttpRangeClient` port), `skein-storage`'s `DefaultFileManager` (atomic-move commit + quarantine + rollback), and the `SyncOrchestrator` (`core.sync`) — the state-machine + single error boundary that wires manifest→diff→download→apply and returns a sealed `SyncOutcome`, publishing `SyncEvent`s over an `EventBus`. Note the `FileManager`/`CommitReceipt` **ports live in `skein-core`** (`core.port`); `skein-storage` provides the impl. The orchestrator is deliberately **sequential** — parallelism/I/O sit behind its service ports (`ManifestResolver`, `LocalStateProvider`, `DownloadService`, `ApplyService` in `core.sync.port`), so it is a pure decision-maker tested end-to-end with fakes. Typed errors flow via `SkeinException`/`ErrorCode`/`Recoverability` (`core.error`). The real `HttpRangeClient` adapter (`JdkHttpRangeClient`, `skein-transfer`, over `java.net.http`) and the concrete service ports (`skein-app`: `DefaultDownloadService` wiring the downloader+FileManager and wrapping `TransferException`→`SkeinException`; `DefaultApplyService` committing/quarantining with rollback) are implemented. The full pipeline is proven with an integration test that runs a real localhost `com.sun.net.httpserver.HttpServer` (JDK, no external dep) — that same pattern is the way to integration-test networked code here. `skein-app` is the application-service layer (depends on core+transfer+storage); the loader adapters' Composition Root assembles these. The server side is done: `skein-server` has `ManifestGenerator` (scans a pack dir → split content-addressed manifest + `hash→file` map) and `SkeinHttpServer` (embedded `com.sun.net.httpserver`, serves `/manifest/root`, `/manifest/index/{CATEGORY}`, `/files/{hash}` with `Range` + bearer-token auth). It also serves a **web admin dashboard** at `/admin` (`AdminSurface` + `ManagedPack`): password login → in-memory session token (`X-Admin-Token`), then a JSON API to list/upload/delete files, bump the version, and regenerate. The server reads the pack through a `Supplier<GeneratedPack>` so admin edits (`ManagedPack.regenerate()`) are served immediately. Admin auth (password) is deliberately separate from the client token. The dashboard page is a dependency-free single HTML file at `skein-server/src/main/resources/admin/index.html`. Manifest (de)serialization is `JsonManifestCodec` in `skein-core`, over a tiny dependency-free `Json` reader/writer in `skein-common` (writes keys sorted so encoded bytes — and therefore index/root hashes — are stable). The full server→client loop is proven end-to-end. Client sync now maintains a persisted **install ledger** (`InstalledPathsCache`, `skein-bootstrap`, under `skeinDir()`): `SkeinClientSession` reads it before syncing, `SyncComponents.forSync` builds `DiffOptions.safe(previouslyManaged)` (replacing plain `additiveOnly()`) so `DefaultDiffEngine` only quarantines a local extra file under a managed dir when it's also a member of the ledger, and after a successful sync the ledger is rewritten as `(previouslyManaged ∪ fetchedPaths) ∩ desiredPaths` — both sets captured off the `SyncOrchestrator`'s `DiffComputed` event — so a path is only ever recorded as Skein-installed if some sync genuinely fetched it (never merely because it was `unchanged`/already-desired), which lets Skein finally clean up a mod the pack drops or renames while still never touching a file the player added themselves, on any sync, ever. **The Minecraft glue**: `skein-neoforge` + `skein-forge` (mod entry, networking over `ControlChannel`, progress GUI, Composition Root). `skein-forge` builds successfully here via `./gradlew :skein-forge:build` (see "Loader adapter modules" above) — verify any change to it that way. `skein-neoforge` remains commented out of the build and unverified in this environment; don't write its loader-API code from memory and claim it works without compiling via Gradle.
