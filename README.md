# ![](https://github.com/sourceplusplus/sourceplusplus/blob/master/.github/media/sourcepp_logo.svg)

[![License](https://camo.githubusercontent.com/93398bf31ebbfa60f726c4f6a0910291b8156be0708f3160bad60d0d0e1a4c3f/68747470733a2f2f696d672e736869656c64732e696f2f6769746875622f6c6963656e73652f736f75726365706c7573706c75732f6c6976652d706c6174666f726d)](LICENSE)
![GitHub release](https://img.shields.io/github/v/release/sourceplusplus/probe-jvm?include_prereleases)
[![Build](https://github.com/sourceplusplus/probe-jvm/actions/workflows/build.yml/badge.svg)](https://github.com/sourceplusplus/probe-jvm/actions/workflows/build.yml)

# What is this?

This project provides JVM support to the [Source++](https://github.com/sourceplusplus/sourceplusplus) open-source live coding platform.

# How to use?

## Gradle Plugin

1. Configure the [Gradle Application Plugin](https://docs.gradle.org/current/userguide/application_plugin.html)
1. Add the following to the `build.gradle` file:
   ```groovy
   plugins {
       id("com.ryandens.javaagent-application") version "0.3.2"
   }
   repositories {
       maven { url "https://pkg.sourceplus.plus/sourceplusplus/probe-jvm" }
   }
   dependencies {
       javaagent("plus.sourceplus.probe:probe-jvm:0.7.0")
   }
   ```

## Standalone Agent

1. Add `spp-probe-*.jar` and `spp-probe.yml` to the same directory
    - E.g. [spp-probe-0.7.0.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.7.0/spp-probe-0.7.0.jar) & [spp-probe.yml](https://docs.sourceplusplus.com/implementation/tools/probe/configuration/)
1. Boot application with `-javaagent:spp-probe-*.jar` parameter
    - E.g. `java -javaagent:/opt/spp-platform/spp-probe-0.7.0.jar -jar MyApp.jar`

## Apache SkyWalking Plugin

1. Add `spp-probe-*.jar` and `spp-skywalking-services-*.jar` to `skywalking-agent/plugins` directory
    - E.g. [spp-probe-0.7.0.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.7.0/spp-probe-0.7.0.jar) & [spp-skywalking-services-0.7.0.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.7.0/spp-skywalking-services-0.7.0.jar)
1. Add `spp-probe.yml` to `skywalking-agent/config` directory
    - E.g. [spp-probe.yml](https://docs.sourceplusplus.com/implementation/tools/probe/configuration/)
1. Reboot Apache SkyWalking agent
