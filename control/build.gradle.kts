import java.util.*

plugins {
    id("com.github.johnrengelman.shadow")
    id("java")
    id("org.jetbrains.kotlin.jvm")
}

val probeGroup: String by project
val probeVersion: String by project
val skywalkingAgentVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project
val jupiterVersion: String by project
val logbackVersion: String by project
val protocolVersion: String by project

group = probeGroup
version = probeVersion

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

dependencies {
    compileOnly(files("$projectDir/../.ext/skywalking-agent-$skywalkingAgentVersion.jar"))
    implementation("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.github.sourceplusplus.protocol:protocol:$protocolVersion") {
        isTransitive = false
    }

    testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("io.vertx:vertx-web-client:$vertxVersion")
    testImplementation("ch.qos.logback:logback-classic:$logbackVersion")
}

tasks.getByName<Test>("test") {
    failFast = true
    useJUnitPlatform()
    if (System.getProperty("test.profile") != "integration") {
        exclude("integration/**")
    } else {
        jvmArgs = listOf("-javaagent:../e2e/spp-probe-${project.version}.jar")
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
            p["build_version"] = project.version.toString()
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

tasks.register<Copy>("updateSkywalkingAgentConfiguration") {
    dependsOn("untarSkywalkingAgent")
    from(File(projectDir, "agent.config"))
    into(File(projectDir.parentFile, "e2e/skywalking-agent/config"))
}

tasks.register<Zip>("zipSppSkywalkingAgent") {
    if (findProject(":probes:jvm") != null) {
        dependsOn("untarSkywalkingAgent", ":probes:jvm:services:shadowJar", "updateSkywalkingAgentConfiguration")
        mustRunAfter(":probes:jvm:services:shadowJar")
    } else {
        dependsOn("untarSkywalkingAgent", ":services:shadowJar", "updateSkywalkingAgentConfiguration")
        mustRunAfter(":services:shadowJar")
    }

    archiveFileName.set("skywalking-agent-$skywalkingAgentVersion.zip")
    val resourcesDir = File("$buildDir/resources/main")
    resourcesDir.mkdirs()
    destinationDirectory.set(resourcesDir)

    from(File(projectDir.parentFile, "e2e/skywalking-agent"))
    into("plugins") {
        doFirst {
            if (!File(projectDir, "../services/build/libs/spp-skywalking-services-$version.jar").exists()) {
                throw GradleException("Missing spp-skywalking-services")
            }
        }
        from(File(projectDir, "../services/build/libs/spp-skywalking-services-$version.jar"))
    }
}
tasks["classes"].dependsOn("zipSppSkywalkingAgent")

tasks.getByName<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    onlyIf { project.tasks.getByName("build").enabled }
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
    relocate("spp.protocol", "spp.probe.common.spp.protocol")
}
tasks.getByName("build").dependsOn("shadowJar")
