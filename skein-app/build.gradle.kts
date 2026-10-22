// skein-app: the application service layer that implements the orchestrator's
// service ports (from skein-core) by wiring together the transfer and storage
// engines. This is where the download coordinator and apply logic live. Pure
// Java; no Minecraft. The loader adapters' Composition Root assembles these.

dependencies {
    implementation(project(":skein-core"))
    implementation(project(":skein-transfer"))
    implementation(project(":skein-storage"))
    implementation(project(":skein-common"))

    testImplementation(project(":skein-core"))
    testImplementation(project(":skein-transfer"))
    testImplementation(project(":skein-storage"))
    testImplementation(libs.bundles.testing)
}
