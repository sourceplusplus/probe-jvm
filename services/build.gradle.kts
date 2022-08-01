plugins {
    id("com.github.johnrengelman.shadow")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

val probeGroup: String by project
val projectVersion: String by project
val skywalkingAgentVersion: String by project
val gsonVersion: String by project
val jacksonVersion: String by project

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
    implementation("plus.sourceplus:protocol:$projectVersion") {
        isTransitive = false
    }
    compileOnly("org.apache.skywalking:apm-agent-core:$skywalkingAgentVersion")
    compileOnly("net.bytebuddy:byte-buddy:1.12.13")

    //implementation("com.google.code.gson:gson:$gsonVersion")
    implementation(files("../.ext/gson-2.8.6-SNAPSHOT.jar"))
    implementation("org.springframework:spring-expression:5.3.22")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    implementation("org.jetbrains:annotations:23.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:4.+")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")
}

tasks {
    shadowJar {
        archiveBaseName.set("spp-skywalking-services")
        archiveClassifier.set("")
        exclude("META-INF/native-image/**")
        exclude("META-INF/vertx/**")
        exclude("module-info.class")
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
    }
    getByName("jar").dependsOn("shadowJar")

    test {
        failFast = true
        maxParallelForks = Runtime.getRuntime().availableProcessors() / 2
    }
}
