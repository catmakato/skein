// Plugin resolution for the loader adapters. The ForgeGradle / NeoGradle plugin
// markers live on the Gradle Plugin Portal, but the actual plugin jars are hosted
// on the loaders' own maven repos, so both must be reachable here.
pluginManagement {
    // Plugin versions come from gradle.properties (no hardcoding in build scripts).
    // The `plugins {}` block below can't read `providers`, but settings can read
    // gradle.properties values via delegated properties.
    val forgeGradleVersion: String by settings
    val neogradleVersion: String by settings
    repositories {
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
    }
    plugins {
        id("net.minecraftforge.gradle") version forgeGradleVersion
        id("net.neoforged.gradle.userdev") version neogradleVersion
    }
}

rootProject.name = "skein"

// Phase 0 modules (pure Java, no Minecraft). More modules (transfer, storage,
// server, neoforge, ...) are added in later phases — see design doc Part 1.
include("skein-common")
include("skein-core")
include("skein-platform")
include("skein-transfer")
include("skein-storage")
include("skein-app")
include("skein-server")
include("skein-bootstrap")
include("skein-cli")

// Loader adapters. These require their own Gradle plugin + Minecraft mappings.
// Both skein-neoforge and skein-forge are written and real (see their
// build.gradle.kts + BUILD_AND_VERIFY.md). They can be enabled independently.
include("skein-forge")
// include("skein-neoforge")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Loader + Minecraft library repos, used by the adapter modules' dependencies.
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://libraries.minecraft.net/")
    }
}
