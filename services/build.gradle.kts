plugins {
    id("com.github.johnrengelman.shadow")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

val probeGroup: String by project
val projectVersion: String by project
val skywalkingAgentVersion: String by project
val gsonVersion: String by project
val vertxVersion: String by project

group = probeGroup
version = project.properties["probeVersion"] as String? ?: projectVersion

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
                artifactId = "probe-jvm-services"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

dependencies {
    compileOnly("plus.sourceplus:protocol:$projectVersion")
    compileOnly("io.vertx:vertx-core:$vertxVersion")
    compileOnly("org.apache.skywalking:apm-agent-core:$skywalkingAgentVersion")
    compileOnly("net.bytebuddy:byte-buddy:1.12.18")
    compileOnly(projectDependency(":common"))

    //implementation("com.google.code.gson:gson:$gsonVersion")
    implementation(files("../.ext/gson-2.8.6-SNAPSHOT.jar"))
    implementation("org.springframework:spring-expression:5.3.23")

    testImplementation("plus.sourceplus:protocol:$projectVersion")
    testImplementation("io.vertx:vertx-core:$vertxVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:4.+")
    testImplementation("org.apache.skywalking:apm-agent-core:$skywalkingAgentVersion")
    testImplementation(projectDependency(":common"))
}

tasks {
    shadowJar {
        archiveBaseName.set("spp-skywalking-services")
        archiveClassifier.set("")
        exclude("kotlin/**")
        exclude("org/intellij/**")
        exclude("org/jetbrains/**")
        exclude("META-INF/native-image/**")
        exclude("META-INF/vertx/**")
        exclude("META-INF/maven/**")
        exclude("META-INF/services/com.fasterxml.jackson.core.JsonFactory")
        exclude("META-INF/services/com.fasterxml.jackson.core.ObjectCodec")
        exclude("META-INF/services/io.vertx.core.spi.launcher.CommandFactory")
        exclude("META-INF/services/reactor.blockhound.integration.BlockHoundIntegration")
        exclude("META-INF/services/org.apache.commons.logging.LogFactory")
        exclude("META-INF/LICENSE")
        exclude("META-INF/NOTICE")
        exclude("META-INF/license.txt")
        exclude("META-INF/notice.txt")
        exclude("META-INF/io.netty.versions.properties")
        exclude("META-INF/spring-core.kotlin_module")
        exclude("META-INF/kotlin-coroutines.kotlin_module")
        exclude("org/springframework/cglib/util/words.txt")
        exclude("org/springframework/expression/spel/generated/SpringExpressions.g")
        relocate("com.google.gson", "spp.probe.services.dependencies.com.google.gson")
        relocate("org.apache.commons", "spp.probe.services.dependencies.org.apache.commons")
        relocate("org.springframework", "spp.probe.services.dependencies.org.springframework")
        relocate("net.bytebuddy", "org.apache.skywalking.apm.dependencies.net.bytebuddy")

        //can't relocate these during testing
        if (System.getProperty("test.profile") != "integration") {
            relocate("io", "spp.probe.common.io")
        }

        relocate("kotlin", "spp.probe.common.kotlin")
        relocate("org.intellij", "spp.probe.common.org.intellij")
        relocate("org.jetbrains", "spp.probe.common.org.jetbrains")
    }
    getByName("jar").dependsOn("shadowJar")

    test {
        failFast = true

        testLogging {
            events("passed", "skipped", "failed")
            setExceptionFormat("full")

            outputs.upToDateWhen { false }
            showStandardStreams = true
        }
    }
}

fun projectDependency(name: String): ProjectDependency {
    return if (rootProject.name.contains("jvm")) {
        DependencyHandlerScope.of(rootProject.dependencies).project(name)
    } else {
        DependencyHandlerScope.of(rootProject.dependencies).project(":probes:jvm$name")
    }
}
