// skein-storage: the File Manager (atomic commit, quarantine) and, later, the
// fingerprint cache. Pure Java over java.nio; no Minecraft.

dependencies {
    implementation(project(":skein-core"))
    implementation(project(":skein-common"))

    testImplementation(project(":skein-core"))
    testImplementation(project(":skein-common"))
    testImplementation(libs.bundles.testing)
}
