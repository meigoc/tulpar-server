import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    id("com.gradleup.shadow") version "9.4.2"
}

group = "meigo.tulpar.server"
version = "2.0.0"

val ktorVersion = "3.3.3"
val cliktVersion = "5.0.3"
val mordantVersion = "3.0.2"
val hopliteVersion = "2.8.0"
val logbackVersion = "1.5.23"
val serializationVersion = "1.7.3"
val commonsCompressVersion = "1.27.1"
val xzVersion = "1.10"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor (server)
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-http-redirect:$ktorVersion")

    // CLI (Clikt)
    implementation("com.github.ajalt.clikt:clikt:$cliktVersion")
    implementation("com.github.ajalt.clikt:clikt-markdown:$cliktVersion")

    // UI & ANSI (Mordant)
    implementation("com.github.ajalt.mordant:mordant:$mordantVersion")
    implementation("com.github.ajalt.mordant:mordant-coroutines:$mordantVersion")

    // Config (Hoplite + HOCON)
    implementation("com.sksamuel.hoplite:hoplite-core:$hopliteVersion")
    implementation("com.sksamuel.hoplite:hoplite-hocon:$hopliteVersion")

    // JSON (kotlinx.serialization) — repodata.json + tolerant metadata parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // Archive: read .apg (tar.xz) natively — replaces the old Jython apgunpacker
    implementation("org.apache.commons:commons-compress:$commonsCompressVersion")
    implementation("org.tukaani:xz:$xzVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("meigo.tulpar.server.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.build {
    dependsOn("shadowJar")
}
