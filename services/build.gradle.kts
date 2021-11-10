plugins {
    id("com.github.johnrengelman.shadow")
    id("java")
    id("org.jetbrains.kotlin.jvm")
}

val probeGroup: String by project
val probeVersion: String by project
val skywalkingAgentVersion: String by project
val gsonVersion: String by project
val jacksonVersion: String by project
val protocolVersion: String by project

group = probeGroup
version = probeVersion

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

dependencies {
    implementation("com.github.sourceplusplus.protocol:protocol:$protocolVersion") {
        isTransitive = false
    }
    compileOnly(files("$projectDir/../.ext/skywalking-agent-$skywalkingAgentVersion.jar"))
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("org.springframework:spring-expression:5.3.12")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    implementation("org.jetbrains:annotations:22.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:4.+")
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
    }

    build {
        dependsOn("shadowJar")
    }

    test {
        failFast = true
        maxParallelForks = Runtime.getRuntime().availableProcessors() / 2
    }
}
