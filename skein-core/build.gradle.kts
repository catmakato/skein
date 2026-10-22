// skein-core: the heart of Skein. Pure Java domain + application logic.
// HARD RULE: no dependency on Minecraft/NeoForge, and no dependency on any
// module other than skein-common. Enforced by an ArchUnit test in later phases.

dependencies {
    implementation(project(":skein-common"))

    testImplementation(project(":skein-common"))
    testImplementation(libs.bundles.testing)
}
