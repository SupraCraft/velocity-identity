plugins {
    `java-library`
    id("xyz.jpenilla.run-velocity") version "3.1.0"
}

group = "io.github.supracraft"
version = "0.1.0-SNAPSHOT"
description = "Provider-neutral identity and admission for Velocity"

val velocityStableVersion = "4.2.0"
val velocityVersion = providers.gradleProperty("velocityVersion").orElse(velocityStableVersion).get()

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
    compileOnly("com.google.code.gson:gson:2.14.0")

    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    runVelocity {
        velocityVersion(velocityVersion)
        runDirectory = file("run/velocity-$velocityVersion")
    }

    register("printBuildInputs") {
        doLast {
            println("java=25")
            println("gradle=9.7.1")
            println("velocity=$velocityVersion")
            println("run-velocity=3.1.0")
        }
    }
}
