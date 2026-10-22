# Building & verifying `skein-forge` (build machine only)

The `skein-forge` module is the Forge 1.20.1 loader adapter. It is a **mirror image
of `skein-neoforge`** — the only differences are the loader-API package names
(`net.minecraftforge.*` instead of `net.neoforged.*`) for the mod entry,
networking, and event bus. All real sync logic lives in the shared
`skein-bootstrap` (and below), which is fully unit-tested and shared by both
adapters.

## Status: builds ✅ and loads+serves on a real dedicated server ✅ (client GUI not yet exercised)

`./gradlew :skein-forge:build` **succeeds** — it compiles every source file
against the real Forge 47.3.0 / MC 1.20.1 API and packages a **complete,
self-contained mod jar** at `skein-forge/build/libs/skein-forge-0.0.1-SNAPSHOT.jar`
(~200 KB; contains all `com.pathongko.skein.*` classes — the shared modules are
bundled in, see the `jar` task in `build.gradle.kts`).

**Verified live via `./gradlew :skein-forge:runServer`:** a Forge 1.20.1 dedicated
server loads the mod, runs `startServer()` (writes `config/skein-server.properties`,
scans the pack, binds the Skein HTTP server), and reaches `Done (…)! For help, type
"help"`. Hitting `http://localhost:<httpPort>/manifest/root` returns HTTP 200 with a
valid manifest. So the **entire server side works in-game**.

> **Dist split (important):** the `@Mod` class (`SkeinMod`) must NOT reference any
> `net.minecraft.client.*` type, or Forge's `RuntimeDistCleaner` crashes the mod on
> a dedicated server (`Attempted to load class …/Screen for invalid dist
> DEDICATED_SERVER`). All client wiring lives in `client/SkeinClient`, reached only
> through `if (FMLEnvironment.dist == Dist.CLIENT)`. This was hit on a real server
> and fixed. Keep it that way; the same split is mirrored in `skein-neoforge`.

What is **not** yet exercised: the **client** GUI (`SkeinProgressScreen`) and the
full join → sync → restart flow on a physical client (`runClient` needs a display).
The client code compiles and is wired the same way as the verified server path.

> **NeoForge note:** NeoForge has **removed its 1.20.1 (`47.1.x`) artifacts** from
> `maven.neoforged.net` — its metadata now starts at `20.2.x` (MC 1.20.2). So
> `skein-neoforge` cannot resolve a 1.20.1 dependency and stays disabled. Its code
> is the same shape as this module and would build identically against a NeoForge
> version that still exists (i.e. if the pack target moves to MC 1.20.2+).

## Prerequisites

- JDK 17 (a JDK 21 toolchain runs the build fine).
- Network access (ForgeGradle downloads Forge 47.3.0 + MC 1.20.1 mappings on first
  build).
- Everything else is committed (Gradle wrapper 8.9, `gradle.properties` pins
  `forgeVersion=47.3.0`, `forgeGradleVersion=6.0.24`, `minecraftVersion=1.20.1`).

## Step 1 — module is already enabled

`include("skein-forge")` is **already uncommented** in `settings.gradle.kts`, and
`settings.gradle.kts` declares the ForgeGradle plugin version and the loader maven
repos in `pluginManagement`. Nothing to do here — the module builds as part of the
normal Gradle build.

(`skein-neoforge` stays commented — see the NeoForge note above.)

## Step 2 — build

```bash
./gradlew :skein-forge:build
```

The first run downloads Forge 47.3.0 + MC 1.20.1 mappings and decompiles Minecraft
(~1–2 min), then compiles and packages the mod jar. Output:
`skein-forge/build/libs/skein-forge-0.0.1-SNAPSHOT.jar` — drop it in the `mods/`
folder of both the client and the dedicated server (single jar, `side = BOTH`).

## Step 3 — run

Same as NeoForge. On first dedicated-server launch the mod writes a template
`config/skein-server.properties`; edit it (identical keys to the NeoForge module —
see `skein-neoforge/BUILD_AND_VERIFY.md` Step 3 for the full annotated template):

```properties
packDir=skein-pack
httpPort=25585
publicEndpoint=http://your-host:25585
clientToken=
adminPassword=
```

Then:

```bash
./gradlew :skein-forge:runServer    # starts MC server + Skein HTTP server + (optional) /admin
./gradlew :skein-forge:runClient    # join the server; the progress screen should appear
```

(You will need ForgeGradle `runClient`/`runServer` run configs declared in
`skein-forge/build.gradle.kts` per the ForgeGradle 6 `runs { }` DSL — add them on
the build machine.)

## Expected result

Identical to NeoForge: put a mod jar the client lacks into the server's
`packDir/mods/`, join, watch `SkeinProgressScreen` run manifest → diff → download →
apply, then get the **"Downloaded updates — please restart your game"** prompt.
After restarting, the new mod is present and the join fast-paths as up-to-date.

## Known adjustment points (flagged inline as `// confirm on build machine`)

Because Forge 47.x on 1.20.1 is the exact API NeoForge forked from, the adjustment
points are the **same list** as `skein-neoforge/BUILD_AND_VERIFY.md`, just under
`net.minecraftforge.*` packages. Highlights:

### Build script — `build.gradle.kts`
- The ForgeGradle 6 `minecraft { mappings("official", ...) }` extension shape and
  the `"minecraft"(...)` dependency-helper syntax in the Kotlin DSL.
- `processResources` may need an `expand`/`filesMatching("META-INF/mods.toml")`
  step so `${version}` in `mods.toml` is substituted rather than shipped literally.
- The `runs { }` block for `runClient`/`runServer`.

### Networking — `net/SkeinNetwork.java`
1. Package for `SimpleChannel` / `NetworkRegistry` / `NetworkEvent` /
   `PacketDistributor` / `NetworkDirection` — `net.minecraftforge.network` on 47.x.
2. `ResourceLocation` constructor vs `fromNamespaceAndPath`.
3. `NetworkRegistry.ChannelBuilder` chain shape.
4. `registerMessage` no-direction overload vs per-direction `messageBuilder`.
5. `NetworkEvent.Context` — `getSender()` / `enqueueWork` / `setPacketHandled`.
6. `PacketDistributor.PLAYER.with(Supplier<ServerPlayer>)`.
7. `FriendlyByteBuf.writeByteArray` / `readByteArray`.

### Progress screen — `client/SkeinProgressScreen.java`
- Identical to the NeoForge screen (`net.minecraft.*` GUI classes are shared): the
  `Screen.render` / `renderBackground` signatures, `Component.literal`,
  `GuiGraphics.drawCenteredString` / `fill`, `Button.builder(...).bounds(...)`, and
  the `onClose()`-in-`render()` UX check.

### Mod entry — `SkeinMod.java`
- `FMLJavaModLoadingContext.get().getModEventBus()`; `@Mod` /
  `FMLJavaModLoadingContext` under `net.minecraftforge.fml`.
- Game/session events on `MinecraftForge.EVENT_BUS` (**not** `NeoForge.EVENT_BUS`).
- `ClientPlayerNetworkEvent.LoggingIn` as the join trigger, and which thread it
  fires on.
- `FMLPaths.CONFIGDIR` / `FMLPaths.GAMEDIR`; `ModList` mod-version accessor chain;
  source the MC version from the loader instead of the hardcoded `"1.20.1"`.
- `event.enqueueWork(...)` on `FMLDedicatedServerSetupEvent` /
  `FMLClientSetupEvent`.

### Platform impls — `platform/*`
- `Minecraft.getInstance().execute(...)`; `FMLPaths.GAMEDIR.get()`.
- `ForgeLoaderInfo.installedMods()` is now populated in `SkeinClient.onClientSetup()`
  from `ModList.get().getMods()` (`IModInfo#getModId()` / `getVersion()`) — confirm
  those accessor names on 47.x.

## Relationship to skein-neoforge

Keep the two adapters in sync: if you fix an API name or behavior in one, check
whether the mirror file in the other needs the same change. The shared seam
(`ControlChannel`, `ClientPlatform`, the bootstraps) does not change — only the
loader-API lines differ between them.
