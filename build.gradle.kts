import java.io.FileOutputStream
import java.net.URL

plugins {
    id("com.diffplug.spotless") apply false
    id("io.gitlab.arturbosch.detekt")
    id("com.avast.gradle.docker-compose")
    id("org.jetbrains.kotlin.jvm") apply false
}

val probeGroup: String by project
val projectVersion: String by project
val skywalkingAgentVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project

group = probeGroup
version = projectVersion

subprojects {
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io")
    }

    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            licenseHeaderFile(file("../LICENSE-HEADER.txt"))
        }
    }
}

tasks {
    register("downloadSkywalkingAgent") {
        doLast {
            val f = File(projectDir, "e2e/apache-skywalking-java-agent-$skywalkingAgentVersion.tgz")
            if (!f.exists()) {
                println("Downloading Apache SkyWalking - Java agent")
                URL("https://downloads.apache.org/skywalking/java-agent/$skywalkingAgentVersion/apache-skywalking-java-agent-$skywalkingAgentVersion.tgz")
                    .openStream().use { input ->
                        FileOutputStream(f).use { output ->
                            input.copyTo(output)
                        }
                    }
                println("Downloaded Apache SkyWalking - Java agent")
            }
        }
    }

    register<Copy>("updateDockerFiles") {
        if (findProject(":probes:jvm") != null) {
            dependsOn(":probes:jvm:control:build")
        } else {
            dependsOn(":control:build")
        }

        from("control/build/libs/spp-probe-$projectVersion.jar")
        into(File(projectDir, "e2e"))
    }

    register("assembleUp") {
        dependsOn("updateDockerFiles", "composeUp")
    }
    getByName("composeUp").mustRunAfter("updateDockerFiles")
}

dockerCompose {
    dockerComposeWorkingDirectory.set(File("./e2e"))
    removeVolumes.set(true)
    waitForTcpPorts.set(false)
}
