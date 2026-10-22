// skein-bootstrap: loader-agnostic composition root + client/server session state
// machines. Pure Java; no Minecraft. The loader adapters call into this module.

dependencies {
    implementation(project(":skein-common"))
    implementation(project(":skein-core"))
    implementation(project(":skein-platform"))
    implementation(project(":skein-app"))
    implementation(project(":skein-transfer"))
    implementation(project(":skein-storage"))
    implementation(project(":skein-server"))

    testImplementation(project(":skein-core"))
    testImplementation(project(":skein-platform"))
    testImplementation(project(":skein-server"))
    testImplementation(libs.bundles.testing)
}
