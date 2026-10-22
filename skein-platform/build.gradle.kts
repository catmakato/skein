// skein-platform: the loader-facing seam. Pure Java, no Minecraft.
//
// It defines the interfaces every loader adapter (Forge, NeoForge, future Fabric)
// must implement, plus the byte[]-based packet codecs shared by all of them. This
// is what lets a single shared codebase drive multiple mod loaders without any
// duplication or any dependency on net.minecraft.*.

dependencies {
    implementation(project(":skein-core"))
    implementation(project(":skein-common"))

    testImplementation(project(":skein-common"))
    testImplementation(libs.bundles.testing)
}
