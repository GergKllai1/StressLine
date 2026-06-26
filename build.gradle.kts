plugins {
    kotlin("jvm") version "2.1.0"
    application
    id("com.gradleup.shadow") version "8.3.5"
    id("org.jmailen.kotlinter") version "5.0.1"
}

group = "dev.stressline"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("net.java.dev.jna:jna:5.15.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.stressline.MainKt")
    applicationName = "stressline"
}

tasks.test {
    useJUnitPlatform()
}
