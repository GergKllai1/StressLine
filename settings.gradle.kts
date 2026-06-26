plugins {
    // Auto-provisions the JVM toolchain (JDK 17) when it isn't already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "StressLine"
