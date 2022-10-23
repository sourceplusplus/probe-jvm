plugins {
    id("org.jetbrains.kotlin.jvm")
}

val probeGroup: String by project
val projectVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project

group = probeGroup
version = project.properties["probeVersion"] as String? ?: projectVersion

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

dependencies {
    compileOnly("plus.sourceplus:protocol:$projectVersion")
    compileOnly("io.vertx:vertx-core:$vertxVersion")
    compileOnly("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
}
