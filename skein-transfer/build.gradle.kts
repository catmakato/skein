// skein-transfer: the download engine — resume, retry/backoff, verification, and
// the HTTP range client. Pure Java (the HTTP client uses the JDK's java.net.http,
// so there are still no external runtime dependencies). No Minecraft.

dependencies {
    implementation(project(":skein-core"))
    implementation(project(":skein-common"))

    testImplementation(project(":skein-core"))
    testImplementation(project(":skein-common"))
    testImplementation(libs.bundles.testing)
}
