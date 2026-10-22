// skein-server: the server-side pieces — scan a pack directory into a manifest
// (ManifestGenerator) and serve manifests + content-addressed files over an
// embedded HTTP server (com.sun.net.httpserver, JDK). Pure Java; can run headless
// without Minecraft.

dependencies {
    implementation(project(":skein-core"))
    implementation(project(":skein-common"))

    testImplementation(project(":skein-core"))
    testImplementation(project(":skein-common"))
    testImplementation(libs.bundles.testing)
}
