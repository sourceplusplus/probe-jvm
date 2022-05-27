import java.util.*

plugins {
    id("com.github.johnrengelman.shadow")
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

val probeGroup: String by project
val projectVersion: String by project
val skywalkingAgentVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project
val jupiterVersion: String by project
val logbackVersion: String by project
val probeVersion: String by project

group = probeGroup
version = probeVersion

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sourceplusplus/probe-jvm")
            credentials {
                username = System.getenv("GH_PUBLISH_USERNAME")?.toString()
                password = System.getenv("GH_PUBLISH_TOKEN")?.toString()
            }
        }
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = probeGroup
                artifactId = "probe-jvm"
                version = projectVersion

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    compileOnly("org.apache.skywalking:apm-agent-core:$skywalkingAgentVersion")
    implementation("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("plus.sourceplus:protocol:$projectVersion") {
        isTransitive = false
    }

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("io.vertx:vertx-web-client:$vertxVersion")
    testImplementation("ch.qos.logback:logback-classic:$logbackVersion")
    testImplementation("io.vertx:vertx-service-proxy:$vertxVersion")
    testImplementation("io.vertx:vertx-service-discovery:$vertxVersion")
    testImplementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
}

tasks.getByName<Test>("test") {
    failFast = true
    useJUnitPlatform()
    if (System.getProperty("test.profile") != "integration") {
        exclude("integration/**")
    }

    testLogging {
        events("passed", "skipped", "failed")
        setExceptionFormat("full")

        outputs.upToDateWhen { false }
        showStandardStreams = true
    }
}

//todo: shouldn't need to put in src (github actions needs for some reason)
tasks.create("createProperties") {
    if (System.getProperty("build.profile") == "release") {
        val buildBuildFile = File(projectDir, "src/main/resources/build.properties")
        if (buildBuildFile.exists()) {
            buildBuildFile.delete()
        } else {
            buildBuildFile.parentFile.mkdirs()
        }

        buildBuildFile.writer().use {
            val p = Properties()
            p["build_id"] = UUID.randomUUID().toString()
            p["build_date"] = Date().toInstant().toString()
            p["build_version"] = probeVersion
            p["apache_skywalking_version"] = skywalkingAgentVersion
            p.store(it, null)
        }
    }
}
tasks["processResources"].dependsOn("createProperties")

tasks.register<Copy>("untarSkywalkingAgent") {
    if (findProject(":probes:jvm") != null) {
        dependsOn(":probes:jvm:downloadSkywalkingAgent")
    } else {
        dependsOn(":downloadSkywalkingAgent")
    }
    from(tarTree(resources.gzip(File(projectDir.parentFile, "e2e/apache-skywalking-java-agent-$skywalkingAgentVersion.tgz"))))
    into(File(projectDir.parentFile, "e2e"))
}

tasks.register<Zip>("zipSppSkywalkingAgent") {
    if (findProject(":probes:jvm") != null) {
        dependsOn("untarSkywalkingAgent", ":probes:jvm:services:shadowJar")
        mustRunAfter(":probes:jvm:services:shadowJar")
    } else {
        dependsOn("untarSkywalkingAgent", ":services:shadowJar")
        mustRunAfter(":services:shadowJar")
    }

    archiveFileName.set("skywalking-agent-$skywalkingAgentVersion.zip")
    val resourcesDir = File("$buildDir/resources/main")
    resourcesDir.mkdirs()
    destinationDirectory.set(resourcesDir)

    from(File(projectDir.parentFile, "e2e/skywalking-agent"))
    into("plugins") {
        doFirst {
            if (!File(projectDir, "../services/build/libs/spp-skywalking-services-$projectVersion.jar").exists()) {
                throw GradleException("Missing spp-skywalking-services")
            }
        }
        from(File(projectDir, "../services/build/libs/spp-skywalking-services-$projectVersion.jar"))
    }
}
tasks["classes"].dependsOn("zipSppSkywalkingAgent")

tasks.getByName<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    if (findProject(":probes:jvm") != null) {
        dependsOn(":probes:jvm:downloadSkywalkingAgent")
    } else {
        dependsOn(":downloadSkywalkingAgent")
    }

    archiveBaseName.set("spp-probe")
    archiveClassifier.set("")
    exclude("module-info.class")
    exclude("META-INF/**")
    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "spp.probe.SourceProbe",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true"
            )
        )
    }

    relocate("kotlin", "spp.probe.common.kotlin")
    relocate("org.intellij", "spp.probe.common.org.intellij")
    relocate("org.jetbrains", "spp.probe.common.org.jetbrains")
    relocate("org.yaml", "spp.probe.common.org.yaml")
    relocate("io", "spp.probe.common.io")
    relocate("com.fasterxml", "spp.probe.common.com.fasterxml")

    //need to move marshall package to allow probe to observe platform
    relocate("spp.protocol.marshall", "spp.protocol")
}
tasks.getByName("jar").dependsOn("shadowJar")
