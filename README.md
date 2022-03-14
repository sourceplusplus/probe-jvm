# ![](https://github.com/sourceplusplus/live-platform/blob/master/.github/media/sourcepp_logo.svg)

[![License](https://camo.githubusercontent.com/93398bf31ebbfa60f726c4f6a0910291b8156be0708f3160bad60d0d0e1a4c3f/68747470733a2f2f696d672e736869656c64732e696f2f6769746875622f6c6963656e73652f736f75726365706c7573706c75732f6c6976652d706c6174666f726d)](LICENSE)
![GitHub release](https://img.shields.io/github/v/release/sourceplusplus/probe-jvm?include_prereleases)
[![Build](https://github.com/sourceplusplus/probe-jvm/actions/workflows/build.yml/badge.svg)](https://github.com/sourceplusplus/probe-jvm/actions/workflows/build.yml)

# What is this?

This project provides JVM support to the [Source++](https://github.com/sourceplusplus/live-platform) open-source live coding platform.

# How to use?

## Standalone Agent

1. Add `spp-probe-*.jar` and `spp-probe.yml` to the same directory
    - E.g. [spp-probe-0.4.2.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.4.2/spp-probe-0.4.2.jar) & [spp-probe.yml](https://docs.sourceplusplus.com/implementation/tools/probe/configuration/)
1. Boot application with `-javaagent:spp-probe-*.jar` parameter
    - E.g. `java -javaagent:/opt/spp-platform/spp-probe-0.4.2.jar -jar MyApp.jar`

## Apache SkyWalking Plugin

1. Add `spp-probe-*.jar` and `spp-skywalking-services-*.jar` to `skywalking-agent/plugins` directory
    - E.g. [spp-probe-0.4.2.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.4.2/spp-probe-0.4.2.jar) & [spp-skywalking-services-0.4.2.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.4.2/spp-skywalking-services-0.4.2.jar)
1. Add `spp-probe.yml` to `skywalking-agent/config` directory
    - E.g. [spp-probe.yml](https://docs.sourceplusplus.com/implementation/tools/probe/configuration/)
1. Reboot Apache SkyWalking agent
