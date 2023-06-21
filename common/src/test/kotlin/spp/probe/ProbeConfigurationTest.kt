/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spp.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProbeConfigurationTest {

    @Test
    fun configEnv() {
        val configFile = javaClass.classLoader.getResource("spp-probe-env.yml")!!.file
        val config = ProbeConfiguration.loadConfigProperties(configFile)
        config.getJsonObject("spp").let {
            assertEquals("127.0.0.1", it.getString("platform_host"))
            assertEquals(1234, it.getString("platform_port").toInt())
        }
        config.getJsonObject("skywalking").let {
            assertEquals("INFO", it.getJsonObject("logging").getString("level"))
            assertEquals("test", it.getJsonObject("agent").getString("service_name"))
        }

        ProbeConfiguration.localProperties = config
        assertEquals(false, ProbeConfiguration.sslEnabled)
        assertEquals(true, ProbeConfiguration.spp.getValue("verify_host").toString().toBooleanStrict())
        assertEquals(
            false,
            ProbeConfiguration.spp.getValue("delete_probe_directory_on_boot").toString().toBooleanStrict()
        )

        val swSettings = ProbeConfiguration.toSkyWalkingSettings(config)
        assertEquals("INFO", swSettings.find {
            it.first() == "skywalking.logging.level"
        }?.last())
        assertEquals("test", swSettings.find {
            it.first() == "skywalking.agent.service_name"
        }?.last())
        assertEquals("true", swSettings.find {
            it.first() == "skywalking.agent.is_cache_enhanced_class"
        }?.last())
        assertEquals("FILE", swSettings.find {
            it.first() == "skywalking.agent.class_cache_mode"
        }?.last())
        assertEquals("false", swSettings.find {
            it.first() == "skywalking.plugin.toolkit.log.transmit_formatted"
        }?.last())
        assertEquals("127.0.0.1:11800", swSettings.find {
            it.first() == "skywalking.collector.backend_service"
        }?.last())
    }

    @Test
    fun configNoEnv() {
        val configFile = javaClass.classLoader.getResource("spp-probe-no-env.yml")!!.file
        val config = ProbeConfiguration.loadConfigProperties(configFile)
        config.getJsonObject("spp").let {
            assertEquals("localhost", it.getString("platform_host"))
            assertEquals(12800, it.getString("platform_port").toInt())
        }
        config.getJsonObject("skywalking").let {
            assertEquals("WARN", it.getJsonObject("logging").getString("level"))
            assertEquals("jvm", it.getJsonObject("agent").getString("service_name"))
        }

        ProbeConfiguration.localProperties = config
        val swSettings = ProbeConfiguration.toSkyWalkingSettings(config)
        assertEquals("WARN", swSettings.find {
            it.first() == "skywalking.logging.level"
        }?.last())
        assertEquals("jvm", swSettings.find {
            it.first() == "skywalking.agent.service_name"
        }?.last())
        assertEquals("true", swSettings.find {
            it.first() == "skywalking.agent.is_cache_enhanced_class"
        }?.last())
        assertEquals("FILE", swSettings.find {
            it.first() == "skywalking.agent.class_cache_mode"
        }?.last())
        assertEquals("false", swSettings.find {
            it.first() == "skywalking.plugin.toolkit.log.transmit_formatted"
        }?.last())
        assertEquals("localhost:11800", swSettings.find {
            it.first() == "skywalking.collector.backend_service"
        }?.last())
    }
}
