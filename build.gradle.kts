// Root build — shared configuration applied to every module.
// Each module stays small; cross-cutting concerns live here.

plugins {
    java
}

val javaVersion: String by project

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion.toInt()))
        }
    }

    // Test wiring is declared per-module via the version catalog so that
    // production modules that don't need tests stay dependency-free.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all")
    }
}
