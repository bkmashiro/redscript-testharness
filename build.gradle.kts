plugins {
    kotlin("jvm") version "1.9.23"
}

group = "dev.redscript"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API - compile only (provided by server)
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // Gson is bundled with Paper, just need it for compilation
    compileOnly("com.google.code.gson:gson:2.10.1")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    // Include Kotlin stdlib since Paper doesn't bundle it
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
