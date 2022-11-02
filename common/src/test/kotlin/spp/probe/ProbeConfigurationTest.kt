/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022 CodeBrig, Inc.
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

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeConfigurationTest {

    @Test
    fun configEnv() {
        val configFile = javaClass.classLoader.getResource("spp-probe-env.yml")!!.file
        val config = ProbeConfiguration.loadConfigProperties(configFile)
        config.second.getJsonObject("spp").let {
            assertEquals("localhost", it.getString("platform_host"))
            assertEquals(12800, it.getString("platform_port").toInt())
        }
        config.second.getJsonObject("skywalking").let {
            assertEquals("WARN", it.getJsonObject("logging").getString("level"))
            assertEquals("jvm", it.getJsonObject("agent").getString("service_name"))
        }
    }

    @Test
    fun configNoEnv() {
        val configFile = javaClass.classLoader.getResource("spp-probe-no-env.yml")!!.file
        val config = ProbeConfiguration.loadConfigProperties(configFile)
        config.second.getJsonObject("spp").let {
            assertEquals("localhost", it.getString("platform_host"))
            assertEquals(12800, it.getString("platform_port").toInt())
        }
        config.second.getJsonObject("skywalking").let {
            assertEquals("WARN", it.getJsonObject("logging").getString("level"))
            assertEquals("jvm", it.getJsonObject("agent").getString("service_name"))
        }
    }
}
