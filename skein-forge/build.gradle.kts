// skein-forge is the Forge adapter (MC 1.20.1) — a mirror image of skein-neoforge.
// The ONLY differences from the NeoForge module are the loader API calls (mod entry,
// networking, event bus). Everything else is reused from :skein-bootstrap and the
// other shared modules, so no logic is duplicated between the two adapters.
//
// This module compiles and packages a complete, self-contained mod jar via
// `./gradlew :skein-forge:build` (ForgeGradle downloads Forge 47.3.0 + MC 1.20.1
// mappings on first run). See skein-forge/BUILD_AND_VERIFY.md.
plugins {
    // Version is declared in settings.gradle.kts pluginManagement (sourced from
    // gradle.properties: forgeGradleVersion).
    id("net.minecraftforge.gradle")
}

val minecraftVersion: String by project
val forgeVersion: String by project

// The shared Skein modules whose classes must be visible to the mod. In a packaged
// jar they are bundled in (see the `jar` task); in the dev run (runClient/runServer)
// they are external project jars, so each must be added as an extra mod source or
// ModLauncher can't find e.g. com.pathongko.skein.core.port.HashService.
val sharedModules = listOf(
    ":skein-common", ":skein-core", ":skein-platform", ":skein-transfer",
    ":skein-storage", ":skein-app", ":skein-server", ":skein-bootstrap"
)
sharedModules.forEach { evaluationDependsOn(it) }

// ForgeGradle 6 DSL, verified against forgeGradleVersion 6.0.24 on this repo.
minecraft {
    // Official Mojang mappings for 1.20.1 (same channel skein-neoforge uses).
    mappings("official", minecraftVersion)

    runs {
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create("skein") {
                    source(sourceSets["main"])
                    sharedModules.forEach { p ->
                        source(project(p).extensions.getByType(SourceSetContainer::class.java).getByName("main"))
                    }
                }
            }
        }
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create("skein") {
                    source(sourceSets["main"])
                    sharedModules.forEach { p ->
                        source(project(p).extensions.getByType(SourceSetContainer::class.java).getByName("main"))
                    }
                }
            }
        }
    }
}

dependencies {
    // ForgeGradle's `minecraft(...)` helper pulls the combined MC + Forge userdev
    // artifact ("<mc>-<forge>"), analogous to NeoGradle's `net.neoforged:neoforge`.
    "minecraft"("net.minecraftforge:forge:$minecraftVersion-$forgeVersion")

    // skein-bootstrap deps are `implementation` (not `api`), so declare each module
    // whose types this adapter names directly. bootstrap gives ClientBootstrap/
    // ServerBootstrap/SkeinServerSession/SyncComponents; the others below are named
    // directly by SkeinMod / the platform impls.
    implementation(project(":skein-bootstrap"))
    implementation(project(":skein-platform"))
    implementation(project(":skein-core"))
    implementation(project(":skein-storage"))
    implementation(project(":skein-server"))
    implementation(project(":skein-common"))
}

// Substitute ${version} (and any future ${...} placeholders) in mods.toml from
// gradle.properties, so the mod's declared version is never hardcoded twice.
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") {
        expand(props)
    }
}

// The shared Skein modules (skein-bootstrap and everything it pulls in) are plain
// Java libraries, not mods — Forge won't find them at runtime unless they ride
// inside this mod jar. Merge only the skein-* project jars into the mod jar (never
// the Forge/Minecraft jars, which the game already provides). reobfJar wraps this
// output; the shared classes carry no Minecraft references, so reobf leaves them
// untouched.
tasks.named<Jar>("jar") {
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("skein-") && it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// ForgeGradle runs (client/server) — declare the `runs { create("client") { } }`
// block per the ForgeGradle 6 DSL on the build machine.
