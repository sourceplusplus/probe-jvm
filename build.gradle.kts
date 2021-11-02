import java.io.FileOutputStream
import java.net.URL

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("com.avast.gradle.docker-compose")
    id("org.jetbrains.kotlin.jvm") apply false
}

val probeGroup: String by project
val probeVersion: String by project
val skywalkingAgentVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project

group = probeGroup
version = probeVersion

subprojects {
    repositories {
        mavenCentral()
        jcenter()
        maven(url = "https://kotlin.bintray.com/kotlinx/")
        maven(url = "https://jitpack.io")
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
        dependsOn(":control:build")

        from("control/build/libs/spp-probe-$version.jar")
        into(File(projectDir, "e2e"))
    }
}

dockerCompose {
    dockerComposeWorkingDirectory.set(File("./e2e"))
    removeVolumes.set(true)
    waitForTcpPorts.set(false)
}
