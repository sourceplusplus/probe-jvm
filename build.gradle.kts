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
version = project.properties["probeVersion"] as String? ?: projectVersion

repositories {
    mavenCentral()
}

subprojects {
    repositories {
        mavenCentral()
        maven(url = "https://pkg.sourceplus.plus/sourceplusplus/protocol")
    }

    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            val startYear = 2022
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val copyrightYears = if (startYear == currentYear) {
                "$startYear"
            } else {
                "$startYear-$currentYear"
            }

            val probeProject = findProject(":probes:jvm") ?: rootProject
            val licenseHeader = Regex("( . Copyright [\\S\\s]+)")
                .find(File(probeProject.projectDir, "LICENSE").readText())!!
                .value.lines().joinToString("\n") {
                    if (it.trim().isEmpty()) {
                        " *"
                    } else {
                        " * " + it.trim()
                    }
                }
            val formattedLicenseHeader = buildString {
                append("/*\n")
                append(
                    licenseHeader.replace(
                        "Copyright [yyyy] [name of copyright owner]",
                        "Source++, the continuous feedback platform for developers.\n" +
                                " * Copyright (C) $copyrightYears CodeBrig, Inc."
                    ).replace(
                        "http://www.apache.org/licenses/LICENSE-2.0",
                        "    http://www.apache.org/licenses/LICENSE-2.0"
                    )
                )
                append("/")
            }
            licenseHeader(formattedLicenseHeader)
        }
    }
}

tasks {
    register("downloadSkywalkingAgent") {
        doLast {
            val f = File(buildDir, "agent/apache-skywalking-java-agent-$skywalkingAgentVersion.tgz")
            if (!f.exists()) {
                f.parentFile.mkdirs()
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

    register("assembleUp") {
        if (findProject(":probes:jvm") != null) {
            dependsOn(":probes:jvm:boot:build", "composeUp")
        } else {
            dependsOn(":boot:build", "composeUp")
        }
    }
}

dockerCompose {
    waitForTcpPorts.set(false)
}
