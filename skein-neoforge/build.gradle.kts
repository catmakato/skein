// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
plugins {
    // Version is declared in settings.gradle.kts pluginManagement (sourced from
    // gradle.properties: neogradleVersion).
    id("net.neoforged.gradle.userdev")
}

val minecraftVersion: String by project
val neoforgeVersion: String by project

dependencies {
    implementation("net.neoforged:neoforge:$neoforgeVersion")

    // skein-bootstrap deps are `implementation` (not `api`), so declare each module
    // whose types this adapter names directly. bootstrap gives ClientBootstrap/
    // ServerBootstrap/SkeinServerSession/SyncComponents; the others below are named
    // directly by SkeinMod / the platform impls.
    implementation(project(":skein-bootstrap"))
    implementation(project(":skein-platform"))
    implementation(project(":skein-core"))
    implementation(project(":skein-server"))
    implementation(project(":skein-common"))
}

// NeoGradle runs (client/server) — declare per the NeoGradle 7 DSL on the build machine.
