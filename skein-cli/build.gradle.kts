// skein-cli: a small runnable demo that ties the shared modules together so the
// whole sync can be exercised from the command line (no Minecraft needed):
//   ./gradlew :skein-cli:run --args="server <packDir> <port> [token]"
//   ./gradlew :skein-cli:run --args="sync <http://host:port> <targetDir> [token]"

plugins {
    application
}

dependencies {
    implementation(project(":skein-core"))
    implementation(project(":skein-common"))
    implementation(project(":skein-transfer"))
    implementation(project(":skein-storage"))
    implementation(project(":skein-app"))
    implementation(project(":skein-server"))
    implementation(project(":skein-bootstrap"))
}

application {
    mainClass.set("com.pathongko.skein.cli.SkeinCli")
}
