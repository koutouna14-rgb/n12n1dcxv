plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.github.tejaslamba2006"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.build {
    dependsOn(tasks.jar)
}

tasks.runServer {
    // Pulls the latest Paper build for this version automatically.
    minecraftVersion("1.21.11")
    downloadPlugins {
        hangar("ProtocolLib", "5.4.0") // swap for the newest ProtocolLib build that lists 1.21.11 support
    }
}
