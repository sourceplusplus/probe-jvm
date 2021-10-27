import java.io.FileOutputStream
import java.net.URL

plugins {
    id("java")
    id("com.avast.gradle.docker-compose") version "0.14.9"

    val kotlinVersion = "1.5.0"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("js") version kotlinVersion apply false

    id("io.gitlab.arturbosch.detekt") version "1.18.1"
}

val platformGroup: String by project
val probeVersion: String by project
val skywalkingVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project

group = platformGroup
version = probeVersion

repositories {
    mavenCentral()
    jcenter()
    maven(url = "https://kotlin.bintray.com/kotlinx/")
}

subprojects {
    repositories {
        mavenCentral()
        jcenter()
        maven(url = "https://kotlin.bintray.com/kotlinx/")
        maven(url = "https://jitpack.io")
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    if (name == "control" || name == "services" || name == "protocol") return@subprojects

    apply(plugin = "kotlin-kapt")

    dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
        implementation("io.vertx:vertx-core:$vertxVersion")
        implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
        implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
        implementation("io.vertx:vertx-web:$vertxVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-guava:$jacksonVersion")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
        implementation("io.dropwizard.metrics:metrics-core:4.2.4")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.0")
    }

    apply<io.gitlab.arturbosch.detekt.DetektPlugin>()
    tasks {
        withType<io.gitlab.arturbosch.detekt.Detekt> {
            parallel = true
            buildUponDefaultConfig = true
        }

        withType<JavaCompile> {
            sourceCompatibility = "1.8"
            targetCompatibility = "1.8"
        }
        withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions.apiVersion = "1.4"
            kotlinOptions.jvmTarget = "1.8"
            kotlinOptions.freeCompilerArgs +=
                listOf(
                    "-Xno-optimized-callable-references",
                    "-Xjvm-default=compatibility"
                )
        }

        withType<Test> {
            testLogging {
                events("passed", "skipped", "failed")
                setExceptionFormat("full")

                outputs.upToDateWhen { false }
                showStandardStreams = true
            }
        }
    }
}

tasks {
    register("downloadSkywalking") {
        doLast {
            val f = File(projectDir, "e2e/apache-skywalking-apm-es7-$skywalkingVersion.tar.gz")
            if (!f.exists()) {
                println("Downloading Apache SkyWalking")
                URL("https://archive.apache.org/dist/skywalking/$skywalkingVersion/apache-skywalking-apm-es7-$skywalkingVersion.tar.gz")
                    .openStream().use { input ->
                        FileOutputStream(f).use { output ->
                            input.copyTo(output)
                        }
                    }
                println("Downloaded Apache SkyWalking")
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
