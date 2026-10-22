# Skein

A from-scratch Minecraft modpack synchronizer. When a player joins a server,
Skein checks their mods / configs / resource packs / shader packs / datapacks
against the server's manifest and downloads only what changed — verified with
SHA-256, resumable, and safe to interrupt.

> Original architecture. Not derived from AutoModpack or any other project.
> See the full design document for the reasoning behind every decision.

**First target:** Minecraft 1.20.1 · Java 17 · NeoForge.
File transfer runs over an embedded HTTP server; Minecraft packets carry only
control/metadata (see design doc, ADR-01).

## Module layout (Phase 0)

| Module         | Responsibility                                     | Depends on      |
|----------------|----------------------------------------------------|-----------------|
| `skein-common` | Dependency-free utilities + logging facade         | *(nothing)*     |
| `skein-core`   | Pure-Java domain + application logic (no Minecraft) | `skein-common`  |

Later phases add `skein-transfer`, `skein-storage`, `skein-config`,
`skein-server`, `skein-neoforge`, and `skein-testkit`.

**Hard rule:** `skein-core` must never import `net.minecraft.*` or depend on any
module other than `skein-common`. This keeps the core testable in milliseconds
and portable across Minecraft versions. It will be enforced by an ArchUnit test.

## Building

Requires JDK 17+ (a JDK 21 toolchain is fine — the build targets Java 17). The
Gradle wrapper is committed, so this works out of the box:

```bash
./gradlew build      # compile + run all tests (60 JUnit tests)
./gradlew test
```

## Try it (no Minecraft needed)

The `skein-cli` module is a runnable demo of the whole sync. Build the launcher
once, then run a server and a client against real folders:

```bash
./gradlew :skein-cli:installDist
CLI=./skein-cli/build/install/skein-cli/bin/skein-cli

# terminal 1 — scan a pack folder and serve it
"$CLI" server ./my-pack 25599                        # public file server only
"$CLI" server ./my-pack 25599 --admin-pass=secret    # + web admin at /admin

# terminal 2 — sync a (possibly empty) folder from that server
"$CLI" sync http://127.0.0.1:25599 ./my-client
```

### Web admin

Sign in at `http://127.0.0.1:<port>/admin` (admin password) to manage packs from
the browser: view every file (size + hash) by category, upload mods/configs,
delete files, bump the pack version, and rescan — each change regenerates the
manifest immediately, so the next client sync picks it up. The admin password is
separate from the client token, so players never get management rights.

**Single server** — admin lives on the same port as the pack:

```bash
"$CLI" server ./my-pack 25599 --admin-pass=secret   # UI at :25599/admin
```

**Multi-server control panel** — one dashboard that starts/stops and manages
several packs, each on its own port (add them from the UI):

```bash
"$CLI" panel 25700 --admin-pass=secret               # UI at :25700/admin
```

The client prints each state transition, a diff summary, download progress, and
the final outcome (`UpToDate` / `Applied` / `RestartRequired` / `Failed`). Run it
again with nothing changed to see the fast path (0 bytes, `UpToDate`); change a
file in the pack, restart the server, and sync again to see only the changed
files fetched and removed files moved to `<client>/.skein/quarantine/` (never hard
deleted).

The core has zero external runtime dependencies, so it can also be compiled
directly for a quick sanity check:

```bash
find skein-common/src/main/java skein-core/src/main/java -name '*.java' > sources.txt
javac --release 17 -d out/classes @sources.txt
```

## Status

Done and verified: build skeleton, domain model, hashing service, the
`skein-platform` loader seam (Forge + NeoForge share it), the manifest system +
diff engine (`DefaultDiffEngine`), the download engine (`DefaultFileDownloader` —
resume, retry/backoff, mirror rotation, single-pass hash verification), and the
`FileManager` (`skein-storage` — atomic commit + quarantine + rollback), and the
`SyncOrchestrator` state machine that wires manifest → diff → download → apply and
returns a typed `SyncOutcome` (up-to-date / applied / restart-required / failed /
cancelled), publishing lifecycle events throughout. The real `HttpRangeClient`
adapter (`JdkHttpRangeClient` over `java.net.http`, with byte-range + bearer-token
support) and the concrete services (`skein-app`: `DefaultDownloadService`,
`DefaultApplyService`) are in place — the full pipeline is verified downloading
over a real localhost HTTP server and committing into a temp instance. The
server side is done too: `skein-server` scans a pack directory into a split,
content-addressed manifest (`ManifestGenerator`) and serves manifests + files
(with `Range` + bearer-token auth) over an embedded HTTP server. The whole
server→client loop — generate, serve, fetch manifest, download by hash, verify —
is proven end-to-end. **The only remaining piece is the Minecraft glue**: the
Forge/NeoForge loader adapters (which need the loader Gradle toolchains).
