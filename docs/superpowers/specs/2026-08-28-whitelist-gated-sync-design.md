# Whitelist-gated sync — design

Date: 2026-08-28
Status: approved for planning

## Problem

A server operator wants an admin-dashboard toggle, per managed server, that
restricts pack sync to players on that Minecraft server's whitelist. Today,
sync access is controlled only by an optional shared `clientToken` (a static
bearer secret configured once in `skein-server.properties`); there is no way
to gate by player identity, and no dashboard control over it at all.

## Recommended approach: deny at handshake, using the identity Minecraft already gives us

The server already learns the connecting player's UUID during the game-native
handshake, before any sync work starts: `ControlChannel`'s receive callback
(`ServerBootstrap.install()`, `skein-bootstrap`) hands the packet handler a
`PlayerRef` — and `PlayerRef.id()` is already the player's UUID string
(`skein-platform`'s `ForgePlayerRef`/`NeoForgePlayerRef` wrap `ServerPlayer`).
That value simply isn't threaded into `SkeinServerSession.onHandshake(...)`
yet. This is the natural place to gate: nothing about the identity check
needs new plumbing through Minecraft's networking layer, because the trusted
in-game connection already supplies it.

If the toggle is on and the connecting player's UUID is not present in the
server's `whitelist.json`, the server declines to hand out the sync grant at
all — the player never learns the transfer endpoint or session token for the
game-native path.

**Caveat, stated plainly:** this only closes the in-game path. A whitelisted
player could still hand their granted URL/token to someone else, who could
hit the HTTP file server directly. That is already true of today's
`clientToken` model, so this is not a regression — just not a hard security
boundary. Good enough for gating a private/whitelisted SMP, not intended as
airtight access control.

### Two approaches considered and rejected

- **Keep using `clientToken` as the only access control**, and just add a
  dashboard convenience to "sync clientToken from whitelist.json" — rejected:
  doesn't actually gate by identity, still lets anyone with the token in.
- **Move the whitelist check to the HTTP layer** (mint a short-lived,
  per-player token at handshake, validate it per-request in
  `SkeinHttpServer`) — more airtight (closes the caveat above), but real
  added complexity: token expiry, an in-memory issued-tokens table, wiring a
  player identity into what is currently a plain static-secret check. Not
  worth it for this use case; noted here as the natural next step if the
  caveat above ever becomes a real problem.

## Changes by module

**`skein-platform`** — `SessionGrantS2C` gains a `granted` flag and a
`deniedReason` string, instead of a second packet type (there's no
tagged/multi-shape framing on this channel today, and one flat record keeps
the existing single-packet-type contract intact):

```java
record SessionGrantS2C(
        boolean granted,
        String transferEndpoint,
        String sessionToken,
        String packVersion,
        String rootManifestHash,
        String deniedReason)
```

Existing constructors of `SessionGrantS2C` (`SkeinServerSession.onHandshake`,
tests) need `granted=true, deniedReason=""` added.

**`skein-core`** — add `ErrorCode.ACCESS_DENIED`. On the client, a denial
becomes a normal `SyncOutcome.Failed(ACCESS_DENIED, Recoverability.NEEDS_ADMIN,
deniedReason)` — reusing the terminal-outcome UI `SkeinProgressScreen`
already has for `Failed` (message + Back button), no new UI surface needed.

**`skein-bootstrap`**:
- `SkeinServerSession.onHandshake(PlayerRef sender, HandshakeReplyC2S reply)`
  gains the `sender` parameter (already available at the call site in
  `ServerBootstrap.install()` — it's just not passed through today). If the
  server's whitelist gate is enabled and `sender.id()` isn't whitelisted,
  return a denied grant instead of a real one.
- New `MinecraftWhitelist` class: given a path to `whitelist.json`, reports
  whether a UUID string is present. Re-reads the file on every check (no
  caching) — the file is tiny, this only runs once per join attempt, and it
  means live edits (an admin running `/whitelist add` in-game) take effect
  immediately with no invalidation logic. Uses `skein-common`'s existing
  dependency-free `Json` reader, same as `JsonManifestCodec`. If the gate is
  on but the file is missing or unreadable, **fail closed** (deny everyone,
  log a warning) — the admin explicitly opted into restriction, so silently
  falling back to "allow everyone" would defeat their intent without them
  noticing.
- `ClientBootstrap`'s receive handler branches on `grant.granted()`: on
  `false`, it reports the `Failed`/`NEEDS_ADMIN` outcome above straight to
  the listener and never constructs a `SkeinClientSession`.

**`skein-server`** — `ServerNode` gains a mutable `whitelistGateEnabled`
(`AtomicBoolean`, defaults `false`) with a getter/setter. `AdminSurface` gets
one new endpoint, e.g. `POST /admin/api/whitelist-gate?server=X` with body
`{"enabled": true|false}`; the existing `GET /admin/api/pack?server=X`
response gains a `whitelistGateEnabled` field so the dashboard can render
current state.

**`skein-forge` / `skein-neoforge`** (`SkeinMod.java`, both loaders
symmetrically): pass `node::whitelistGateEnabled` (a `BooleanSupplier`,
`node` being the just-registered `ServerNode`) and a `MinecraftWhitelist`
pointed at the server's `whitelist.json` into the `SkeinServerSession`
constructor. `whitelist.json`'s location: resolved independently of
`packDir` (which an admin may have redirected elsewhere) — same "one level
above the config dir" convention already used for `DEFAULT_PACK_DIR_RELATIVE`,
since that's where Minecraft's own `whitelist.json` actually lives.

**Admin dashboard** (`admin/index.html`) — one checkbox in the existing
per-server panel ("Restrict sync to whitelisted players"), calling the new
endpoint, next to the existing "Pack version" panel.

## Persistence: durable across restarts (revised)

Earlier draft of this design followed `ManagedPack`'s `version` precedent
(dashboard-mutable, not persisted — `SkeinMod` hardcodes
`INITIAL_PACK_VERSION = "1.0.0"` on every boot) and left
`whitelistGateEnabled` resetting to `false` on every restart. **Rejected on
review**: `version` is inert data, so losing it on restart just costs a
manual re-set. `whitelistGateEnabled` is a security control — defaulting to
`false` (= open to everyone) on every restart means "restart the dedicated
server" silently becomes "fail-open," the exact opposite of the fail-closed
behavior this same design uses when `whitelist.json` itself is unreadable.
Minecraft servers restart often (updates, crashes, restart scripts), so this
would be a real, easy-to-miss security regression, not a hygiene nitpick.

Fix: persist the single boolean with a marker file, not a general settings
system (that would be over-scope for one flag):

- `ServerNode.setWhitelistGateEnabled(boolean)` also creates/deletes
  `pack.packDir().resolve(".whitelist-gate-enabled")` (empty file; existence
  = on). No JSON, no schema — just a file-exists check.
- `SkeinMod.java` checks that file's existence at boot — the same spot that
  currently hardcodes `INITIAL_PACK_VERSION` — and seeds `ServerNode`'s
  initial state from it instead of always starting `false`.
- Safe to colocate under `packDir`: `ManifestGenerator` only scans the
  managed category subdirectories (`mods/`, `config/`, …), never loose files
  at `packDir`'s root, so this dotfile never leaks into the served manifest.
- Also log a clear line at boot showing the loaded state ("whitelist gate:
  ON (restored from previous setting)" / "OFF") — cheap, and useful for an
  admin auditing why access looks the way it does — but the marker file is
  the actual fix, not the log line.

This does **not** extend to `version` — that stays exactly as it is today,
out of scope for this feature.

## Testing

- `MinecraftWhitelist`: unit tests over real temp-file `whitelist.json`
  content (present/absent UUID, missing file, malformed JSON).
- `SkeinServerSession.onHandshake`: unit tests for gate-off (always grants,
  unchanged from today), gate-on + whitelisted (grants), gate-on +
  not-whitelisted (denies with a reason), gate-on + unreadable whitelist file
  (denies, fail-closed).
- `SessionGrantS2C.CODEC`: round-trip tests for both granted and denied
  shapes (extends the existing `PacketCodecTest`).
- `ClientBootstrap`: a denied grant produces the expected `Failed`/
  `NEEDS_ADMIN` event on the listener and never starts a `SkeinClientSession`.
- `ServerNode` whitelist-gate marker file: toggling on writes the marker,
  toggling off removes it; a fresh `ServerNode`/`SkeinMod` boot against a
  `packDir` with the marker present seeds `whitelistGateEnabled=true` (and
  `false` when absent) — the restart-durability property this section exists
  to guarantee.
- `skein-forge` build (`:skein-forge:build`) after wiring — same verification
  bar as every other change to that module. `skein-neoforge` stays
  unverified in this environment, mirrored by hand same as the rest of that
  module.
