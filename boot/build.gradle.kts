import java.util.*

plugins {
    id("com.github.johnrengelman.shadow")
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    id("groovy")
}

val probeGroup: String by project
val projectVersion: String by project
val skywalkingAgentVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project
val jupiterVersion: String by project
val logbackVersion: String by project
val slf4jVersion: String by project

group = probeGroup
version = project.properties["probeVersion"] as String? ?: projectVersion

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(project.the<SourceSetContainer>()["main"].allSource)
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
                version = project.version.toString()

                // Ship shadow jar
                artifact("$buildDir/libs/spp-probe-${project.version}.jar")

                // Ship the sources jar
                artifact(sourcesJar)
            }
        }
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class) {
    kotlinOptions.jvmTarget = "1.8"
}

configurations {
    create("swAgent")
}
val swAgent: Configuration = configurations.getByName("swAgent")

dependencies {
    swAgent(files("../build/agent/extracted/skywalking-agent/skywalking-agent.jar"))
    compileOnly("org.apache.skywalking:apm-agent-core:$skywalkingAgentVersion")
    implementation("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
    implementation("io.vertx:vertx-service-proxy:$vertxVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("plus.sourceplus:protocol:$projectVersion") {
        isTransitive = false
    }
    implementation(projectDependency(":common"))
    implementation("org.apache.commons:commons-text:1.10.0")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
    testImplementation("io.vertx:vertx-service-proxy:$vertxVersion")
    testImplementation("io.vertx:vertx-service-discovery:$vertxVersion")
    testImplementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    testImplementation("org.codehaus.groovy:groovy-all:3.0.17")
}

tasks.test {
    val probeJar = "${buildDir}/libs/spp-probe-$version.jar"

    //todo: should have way to distinguish tests that just need platform and tests that attach to self
    val isIntegrationProfile = System.getProperty("test.profile") == "integration"
    val runningSpecificTests = gradle.startParameter.taskNames.contains("--tests")

    //exclude attaching probe to self unless requested
    if (isIntegrationProfile || runningSpecificTests) {
        jvmArgs = listOf("-javaagent:$probeJar=${projectDir}/src/test/resources/spp-test-probe.yml")
    }
    //exclude integration tests unless requested
    if (!isIntegrationProfile && !runningSpecificTests) {
        exclude("integration/**", "**/*IntegrationTest.class", "**/*ITTest.class")
    }

    failFast = true
    useJUnitPlatform()

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
    doFirst {
        File(projectDir.parentFile, "build/agent/extracted").deleteRecursively()
    }

    from(tarTree(resources.gzip(File(projectDir.parentFile, "build/agent/apache-skywalking-java-agent-$skywalkingAgentVersion.tgz"))))
    into(File(projectDir.parentFile, "build/agent/extracted"))
}

tasks.register<Zip>("zipPlatformSkywalkingAgent") {
    if (findProject(":probes:jvm") != null) {
        dependsOn(":probes:jvm:boot:processResources")
    } else {
        dependsOn(":boot:processResources")
    }
    dependsOn("zipSppSkywalkingAgent", "platformJar")
    mustRunAfter("zipSppSkywalkingAgent", "platformJar")

    archiveFileName.set("skywalking-agent-$skywalkingAgentVersion.zip")
    destinationDirectory.set(project.layout.buildDirectory.dir("libs"))

    from(project.zipTree("$buildDir/resources/main/skywalking-agent-$skywalkingAgentVersion.zip")) {
        exclude("skywalking-agent.jar")
    }

    from(file("$buildDir/libs")) {
        include ("skywalking-agent.jar")
    }

    duplicatesStrategy = DuplicatesStrategy.FAIL
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

    from(File(projectDir.parentFile, "build/agent/extracted/skywalking-agent"))
    into("plugins") {
        doFirst {
            if (!File(projectDir, "../services/build/libs/spp-skywalking-services-${project.version}.jar").exists()) {
                throw GradleException("Missing spp-skywalking-services")
            }
        }
        from(File(projectDir, "../services/build/libs/spp-skywalking-services-${project.version}.jar"))
    }
}
tasks["classes"].dependsOn("zipSppSkywalkingAgent", "zipPlatformSkywalkingAgent")

tasks.getByName<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    if (findProject(":probes:jvm") != null) {
        dependsOn(":probes:jvm:downloadSkywalkingAgent")
    } else {
        dependsOn(":downloadSkywalkingAgent")
    }

    archiveBaseName.set("spp-probe")
    archiveClassifier.set("")
    exclude("META-INF/maven/**")
    exclude("META-INF/native-image/**")
    exclude("META-INF/services/com.**")
    exclude("META-INF/services/io.**")
    exclude("META-INF/services/reactor.**")
    exclude("META-INF/versions/**")
    exclude("META-INF/vertx/**")
    exclude("META-INF/LICENSE**")
    exclude("META-INF/NOTICE**")
    exclude("META-INF/MANIFEST.MF")
    exclude("META-INF/**.kotlin_module")
    exclude("META-INF/**.properties")
    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "spp.probe.SourceProbe",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true"
            )
        )
    }

    if (System.getProperty("build.profile") == "release") {
        relocate("kotlin", "spp.probe.common.kotlin")
        relocate("org.apache.commons", "spp.probe.common.org.apache.commons")
        relocate("org.intellij", "spp.probe.common.org.intellij")
        relocate("org.jetbrains", "spp.probe.common.org.jetbrains")
        relocate("org.yaml", "spp.probe.common.org.yaml")
        relocate("io", "spp.probe.common.io")
        relocate("com.fasterxml", "spp.probe.common.com.fasterxml")

        //need to move marshall package to allow probe to observe platform
        relocate("spp.protocol.marshall", "spp.protocol")
    }
}
tasks.getByName("jar").dependsOn("shadowJar")
tasks.getByName("generatePomFileForMavenPublication").dependsOn("shadowJar")

fun projectDependency(name: String): ProjectDependency {
    return if (rootProject.name.contains("jvm")) {
        DependencyHandlerScope.of(rootProject.dependencies).project(name)
    } else {
        DependencyHandlerScope.of(rootProject.dependencies).project(":probes:jvm$name")
    }
}

tasks.named<GroovyCompile>("compileTestGroovy") {
    classpath += files(tasks.compileTestKotlin)
}

tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("platformJar") {
    dependsOn("untarSkywalkingAgent")
    configurations = listOf(swAgent)
    archiveFileName.set("skywalking-agent.jar")

    relocate("org.apache.skywalking.apm.dependencies.io.grpc", "io.grpc")
    relocate("org.apache.skywalking.apm.dependencies.com.google.protobuf", "com.google.protobuf")
    relocate("org.apache.skywalking.apm.dependencies.io.netty", "io.netty")
    //relocate("org.apache.skywalking.apm.dependencies.net.bytebuddy", "net.bytebuddy")
}
tasks.register<Zip>("platformProbeJar") {
    if (findProject(":probes:jvm") != null) {
        dependsOn(":probes:jvm:boot:zipPlatformSkywalkingAgent", ":probes:jvm:boot:shadowJar")
    } else {
        dependsOn(":boot:zipPlatformSkywalkingAgent", ":boot:shadowJar")
    }
    archiveFileName.set("spp-probe-platform-$version.jar")
    destinationDirectory.set(project.layout.buildDirectory.dir("libs"))

    from(project.zipTree("$buildDir/libs/spp-probe-$version.jar")) {
        exclude("skywalking-agent-$skywalkingAgentVersion.zip")
    }

    from(file("$buildDir/libs")) {
        include ("skywalking-agent-$skywalkingAgentVersion.zip")
    }

    duplicatesStrategy = DuplicatesStrategy.FAIL
}
tasks.getByName("jar").dependsOn("platformProbeJar")