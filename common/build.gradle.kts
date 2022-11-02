plugins {
    id("org.jetbrains.kotlin.jvm")
}

val probeGroup: String by project
val projectVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project
val skywalkingAgentVersion: String by project

group = probeGroup
version = project.properties["probeVersion"] as String? ?: projectVersion

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

dependencies {
    compileOnly("plus.sourceplus:protocol:$projectVersion")
    compileOnly("org.apache.skywalking:apm-agent-core:$skywalkingAgentVersion")
    compileOnly("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
    compileOnly("io.vertx:vertx-core:$vertxVersion")
    compileOnly("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    compileOnly("org.apache.commons:commons-text:1.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.vertx:vertx-core:$vertxVersion")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    testImplementation("org.apache.commons:commons-text:1.10.0")
}
