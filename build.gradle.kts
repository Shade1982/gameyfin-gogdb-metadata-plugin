import java.util.jar.Manifest

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

group = "io.github.shade1982.gameyfin.plugins"
val manifestFile = file("src/main/resources/MANIFEST.MF")
val manifestVersion = if (manifestFile.exists()) {
    runCatching {
        Manifest(manifestFile.inputStream()).mainAttributes.getValue("Plugin-Version")
    }.getOrNull()
} else null
version = manifestVersion ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly("org.gameyfin:plugin-api:2.3.3")
    compileOnly("org.pf4j:pf4j:3.13.0")
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    val ktorVersion = "3.1.3"
    implementation("io.ktor:ktor-client-core:$ktorVersion") { exclude(group = "org.slf4j") }
    implementation("io.ktor:ktor-client-cio:$ktorVersion") { exclude(group = "org.slf4j") }
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion") { exclude(group = "org.slf4j") }
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion") { exclude(group = "org.slf4j") }
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    testImplementation("org.gameyfin:plugin-api:2.3.3")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion") { exclude(group = "org.slf4j") }
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true
    archiveBaseName.set("gogdb-plugin")

    manifest {
        from("src/main/resources/MANIFEST.MF")
    }

    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    from(sourceSets.main.get().output)
    from("src/main/resources") {
        include("logo.*")
        into("META-INF/resources")
    }
}
