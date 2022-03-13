# ![](https://github.com/sourceplusplus/live-platform/blob/master/.github/media/sourcepp_logo.svg)

[![License](https://img.shields.io/github/license/sourceplusplus/probe-jvm)](LICENSE)
![GitHub release](https://img.shields.io/github/v/release/sourceplusplus/probe-jvm?include_prereleases)
[![Build](https://github.com/sourceplusplus/probe-jvm/actions/workflows/build.yml/badge.svg)](https://github.com/sourceplusplus/probe-jvm/actions/workflows/build.yml)

# What is this?

This project provides JVM support to the [Source++](https://github.com/sourceplusplus/live-platform) open-source live coding platform.

# How to use?

## Standalone Agent

1. Add `spp-probe-*.jar`& `spp-probe.yml` to the same directory
    - E.g. [spp-probe-0.4.1.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.4.1/spp-probe-0.4.1.jar) & [spp-probe.yml](https://docs.sourceplusplus.com/implementation/tools/probe/configuration/)
1. Boot application with `-javaagent:spp-probe-*.jar` parameter
    - E.g. `java -javaagent:/opt/spp-platform/spp-probe-0.4.1.jar -jar MyApp.jar`

## Apache SkyWalking Plugin

1. Add `spp-probe-*.jar` and `spp-skywalking-services-*.jar` to `skywalking-agent/plugins` directory
    - E.g. [spp-probe-0.4.1.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.4.1/spp-probe-0.4.1.jar) & [spp-skywalking-services-0.4.1.jar](https://github.com/sourceplusplus/probe-jvm/releases/download/0.4.1/spp-skywalking-services-0.4.1.jar)
1. Add `spp-probe.yml` to `skywalking-agent/config` directory
    - E.g. [spp-probe.yml](https://docs.sourceplusplus.com/implementation/tools/probe/configuration/)
1. Reboot Apache SkyWalking agent
