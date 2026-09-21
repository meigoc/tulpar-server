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
val commonsCompressVersion = "1.28.0"
val xzVersion = "1.10"
val zstdJniVersion = "1.5.7-18"

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
    implementation("io.ktor:ktor-server-auto-head-response:$ktorVersion")
    implementation("io.ktor:ktor-server-partial-content:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
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

    // Archive: read .apg natively. libAPG accepts tar under {none, gzip, xz,
    // zstd} (src/archive.c); zstd-jni backs the zstd filter, matching the
    // version bundled by the production 2.0-PREVIEW-1 deployment.
    implementation("org.apache.commons:commons-compress:$commonsCompressVersion")
    implementation("org.tukaani:xz:$xzVersion")
    implementation("com.github.luben:zstd-jni:$zstdJniVersion")

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

// --- Differential conformance against libAPG (see conformance/README.md) ---
//
// The hermetic replay of the recorded oracle goldens runs as part of `test`
// (GoldenCorpusTest). The tasks below re-verify the goldens against a LIVE
// libAPG oracle binary; they are skipped unless -PapgOracle=<path> is given,
// so `./gradlew test` never needs libAPG installed. CI passes the oracle built
// from the pinned libAPG commit.

val apgOracle: String? = providers.gradleProperty("apgOracle").orNull

tasks.register<Exec>("conformanceTest") {
    group = "verification"
    description = "Re-verify recorded conformance goldens against a live libAPG oracle (-PapgOracle=<path>)"
    onlyIf {
        if (apgOracle != null && file(apgOracle).canExecute()) true
        else {
            logger.warn(
                "conformanceTest SKIPPED: no executable libAPG oracle. " +
                    "Build conformance/oracle/apg_oracle.c against the pinned libAPG commit " +
                    "and pass -PapgOracle=/path/to/apg_oracle (mandatory in CI).",
            )
            false
        }
    }
    workingDir = projectDir
    commandLine(
        "python3", "conformance/live-oracle-diff.py",
        apgOracle ?: "/bin/false",
        "src/test/resources/conformance",
    )
}

tasks.register<Exec>("regenerateConformanceGoldens") {
    group = "verification"
    description = "Regenerate all conformance goldens from a live oracle; review the diff before committing"
    onlyIf { apgOracle != null && file(apgOracle).canExecute() }
    workingDir = projectDir
    commandLine(
        "bash", "-c",
        "python3 conformance/matrix-generator.py '${apgOracle ?: "/bin/false"}' src/test/resources/conformance && " +
            "python3 conformance/regenerate-parse-goldens.py '${apgOracle ?: "/bin/false"}' src/test/resources/conformance && " +
            "python3 conformance/regenerate-signature-goldens.py '${apgOracle ?: "/bin/false"}' src/test/resources/conformance",
    )
}
