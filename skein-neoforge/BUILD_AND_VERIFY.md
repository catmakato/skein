# Building & verifying `skein-neoforge` (build machine only)

The `skein-neoforge` module is the NeoForge 1.20.1 loader adapter. It is the
**only** part of Skein that touches `net.minecraft.*` / NeoForge APIs, and it
**cannot be built in the development sandbox** — it needs the NeoGradle toolchain
plus Minecraft mappings downloaded from the network. Every source file that
imports a loader/Minecraft type starts with:

```
// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
```

That banner means exactly what it says: the file was written against the shared
`skein-platform` seam and the NeoForge 47.x API *from documentation*, but was
**never compiled**. Expect to fix a handful of API names on the first build. The
shared logic it delegates to (`skein-bootstrap` and below) is fully unit-tested
and unchanged by this module.

## Prerequisites

- JDK 17 (a JDK 21 toolchain runs the build fine).
- Network access (NeoGradle downloads NeoForge 47.1.106 + MC 1.20.1 mappings on
  first build).
- Everything else is committed (Gradle wrapper 8.9, `gradle.properties` pins
  `neoforgeVersion=47.1.106`, `neogradleVersion=7.0.145`, `minecraftVersion=1.20.1`).

## Step 1 — enable the module

In `settings.gradle.kts`, uncomment the include (it is intentionally left
commented so the sandbox build stays green):

```kotlin
include("skein-neoforge")
```

Leave `skein-forge` commented — that adapter is a later port of the same shape.

## Step 2 — build

```bash
./gradlew :skein-neoforge:dependencies   # sanity: resolves :skein-bootstrap + neoforge
./gradlew :skein-neoforge:build
```

The first `:compileJava` is where the UNVERIFIED files get exercised. Fix any
compile errors (see "Known adjustment points" below), then re-run. **Commit the
fixes back** — the goal is a compiling, committed adapter.

## Step 3 — run

Configure the dedicated server. On first launch the mod writes a template
`config/skein-server.properties`; edit it:

```properties
# directory holding the managed mods/, config/, resourcepacks/, ... to serve
packDir=skein-pack
# port Skein's built-in file server listens on
httpPort=25585
# URL clients use to reach this server's Skein HTTP endpoint (public IP/host + port).
# "localhost" only works for a client on the same machine.
publicEndpoint=http://your-host:25585
# optional bearer token clients must present to sync (blank = none)
clientToken=
# optional password for the web admin dashboard at /admin (blank = disabled)
adminPassword=
```

Then:

```bash
./gradlew :skein-neoforge:runServer    # starts MC server + Skein HTTP server + (optional) /admin
./gradlew :skein-neoforge:runClient    # join the server; the progress screen should appear
```

(You will need NeoGradle `runClient`/`runServer` run configs declared in
`skein-neoforge/build.gradle.kts` per the NeoGradle 7 DSL — add them on the build
machine.)

## Expected result

1. Put a mod jar the client lacks into the server's `packDir/mods/`.
2. Join from the client.
3. `SkeinProgressScreen` appears: manifest → diff → download → apply.
4. Because a mod changed, the screen shows **"Downloaded updates — please restart
   your game"** with a Quit button (v1 does not auto-relaunch).
5. After restarting the client, the new mod is present in its `mods/` and the join
   proceeds with no further download (up-to-date fast path).

## Known adjustment points (flagged inline as `// confirm on build machine`)

These are the spots most likely to need an API-name fix. The stable seam
(`ControlChannel`, `ClientPlatform`, the shared bootstraps) does **not** change —
only the lines below.

### Networking — `net/SkeinNetwork.java` (Task B3)
1. Package for `SimpleChannel` / `NetworkRegistry` / `NetworkEvent` /
   `PacketDistributor` / `NetworkDirection` on NeoForge 47.x.
2. `ResourceLocation` constructor vs `fromNamespaceAndPath`.
3. `ChannelBuilder` chain shape.
4. `registerMessage` no-direction overload vs per-direction `messageBuilder`.
5. `NetworkEvent.Context` — `getSender()` / `enqueueWork` / `setPacketHandled`.
6. `PacketDistributor.PLAYER.with(Supplier<ServerPlayer>)`.
7. `FriendlyByteBuf.writeByteArray` / `readByteArray`.

### Progress screen — `client/SkeinProgressScreen.java` (Task B4)
- `Screen.render(GuiGraphics, int, int, float)` signature for 1.20.1; the
  `renderBackground` overload.
- `Component.literal` vs `translatable`; `GuiGraphics.drawCenteredString` / `fill`
  signatures (and ARGB order for `fill`).
- `Button.builder(...).bounds(...).build()` shape; `isPauseScreen` / `onClose`
  behavior.
- UX check: `onClose()` is called from inside `render()` for the
  `UpToDate`/`Applied` outcomes — confirm no one-frame visual artifact; move to a
  tick/button callback if it flickers.

### Mod entry — `SkeinMod.java` (Task B5)
- Mod event-bus retrieval (`FMLJavaModLoadingContext.get().getModEventBus()` vs a
  constructor-injected `IEventBus`); exact `@Mod` / `FMLJavaModLoadingContext`
  package.
- `ClientPlayerNetworkEvent.LoggingIn` as the join trigger — confirm the event
  name and which thread it fires on (the handshake reply + `setScreen` must run on
  the client thread).
- `FMLPaths.CONFIGDIR` / `FMLPaths.GAMEDIR` accessors; `ModList` mod-version
  accessor chain; source the MC version from the loader instead of the hardcoded
  `"1.20.1"` fallback.
- `event.enqueueWork(...)` on `FMLDedicatedServerSetupEvent` /
  `FMLClientSetupEvent`.

### Platform impls — `platform/*` (Task B2)
- `Minecraft.getInstance().execute(...)` (client game-thread executor).
- `FMLPaths.GAMEDIR.get()` used to construct `NeoForgePlatformPaths`.
- Populate `NeoForgeLoaderInfo.installedMods()` from `ModList.get()` if you want the
  richer client view (currently may be an empty list — acceptable for v1).

## Porting to Forge later

`skein-forge` reuses the exact same `skein-bootstrap` and platform seam. Create a
parallel adapter mirroring these files against ForgeGradle + Forge 47.3.0; only the
loader-API lines differ.
