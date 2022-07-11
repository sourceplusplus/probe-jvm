/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.probe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.vertx.core.json.JsonObject
import spp.probe.SourceProbe.PROBE_ID
import java.io.File
import java.io.FileInputStream
import java.util.stream.Collectors
import kotlin.system.exitProcess

object ProbeConfiguration {

    private var rawProperties: Map<String, Map<String, Any>>? = null
    private var localProperties: JsonObject? = null

    init {
        var localFile = File("spp-probe.yml")
        try {
            //working directory?
            val mapper = ObjectMapper(YAMLFactory())
            if (localFile.exists()) {
                rawProperties = mapper.readValue(FileInputStream(localFile), MutableMap::class.java)
                        as Map<String, Map<String, Any>>?
            }
            if (ProbeConfiguration::class.java.protectionDomain.codeSource.location == null) {
                //ran through SkyWalking
                localFile = File(
                    File(
                        Class.forName(
                            "org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader"
                        ).protectionDomain.codeSource.location.file
                    ).parentFile, "plugins" + File.separatorChar + "spp-probe.yml"
                )
                if (localFile.exists()) {
                    rawProperties = mapper.readValue(FileInputStream(localFile), MutableMap::class.java)
                            as Map<String, Map<String, Any>>?
                }

                localFile = File(
                    File(
                        Class.forName(
                            "org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader"
                        ).protectionDomain.codeSource.location.file
                    ).parentFile, "config" + File.separatorChar + "spp-probe.yml"
                )
                if (localFile.exists()) {
                    rawProperties = mapper.readValue(FileInputStream(localFile), MutableMap::class.java)
                            as Map<String, Map<String, Any>>?
                }
            } else {
                //ran through intellij?
                localFile = File(
                    File(
                        ProbeConfiguration::class.java.protectionDomain.codeSource.location.toURI()
                    ).parent, "spp-probe.yml"
                )
                if (localFile.exists()) {
                    rawProperties = mapper.readValue(FileInputStream(localFile), MutableMap::class.java)
                            as Map<String, Map<String, Any>>?
                }
            }

            //inside jar?
            if (rawProperties == null) {
                rawProperties = mapper.readValue(
                    ProbeConfiguration::class.java.getResourceAsStream("/spp-probe.yml"), MutableMap::class.java
                ) as Map<String, Map<String, Any>>?
            }
            localProperties = JsonObject.mapFrom(rawProperties)
        } catch (e: Exception) {
            System.err.println("Failed to read properties file: $localFile")
            e.printStackTrace()
            exitProcess(-1)
        }
    }

    val skywalking: JsonObject
        get() = localProperties!!.getJsonObject("skywalking")
    val spp: JsonObject
        get() = localProperties!!.getJsonObject("spp")

    fun getString(property: String?): String? {
        return localProperties!!.getJsonObject("spp").getString(property)
    }

    @JvmStatic
    fun setString(property: String, value: String?) {
        localProperties!!.getJsonObject("spp").put(property, value)
    }

    fun getInteger(property: String): Int {
        return localProperties!!.getJsonObject("spp").getInteger(property)
    }

    fun setInteger(property: String, value: Int) {
        localProperties!!.getJsonObject("spp").put(property, value)
    }

    val skywalkingSettings: List<Array<String>>
        get() {
            val settings = toProperties(rawProperties).stream()
                .filter { it[0].startsWith("skywalking.") }
                .collect(Collectors.toList()).toMutableList()
            getSkyWalkingDefaults()
                .filter { default -> settings.stream().noneMatch { it[0] == default[0] } }
                .forEach { settings.add(it) }
            if (settings.stream().noneMatch { it[0] == "skywalking.agent.service_name" } ||
                settings.stream().noneMatch { it[0] == "skywalking.collector.backend_service" }) {
                throw RuntimeException("Missing Apache SkyWalking setup configuration")
            }
            return settings
        }

    private fun getSkyWalkingDefaults(): MutableSet<Array<String>> {
        return setOf(
            arrayOf("skywalking.agent.instance_properties_json", JsonObject().put("probe_id", PROBE_ID).toString()),
            arrayOf("skywalking.agent.is_cache_enhanced_class", "true"),
            arrayOf("skywalking.agent.class_cache_mode", "FILE"),
            arrayOf("skywalking.plugin.toolkit.log.transmit_formatted", "false"),
            sppSettings.find { it[0] == "spp.platform_host" }?.let {
                arrayOf("skywalking.collector.backend_service", it[1] + ":11800")
            }
        ).filterNotNull().toMutableSet()
    }

    val sppSettings: List<Array<String>>
        get() = toProperties(rawProperties).stream()
            .filter { it[0].startsWith("spp.") }
            .collect(Collectors.toList())
    val isNotQuite: Boolean
        get() {
            val quiteMode = getString("quiet_mode") ?: return false
            return "false".equals(quiteMode, ignoreCase = true)
        }

    @JvmStatic
    fun setQuietMode(quiet: Boolean) {
        setString("quiet_mode", java.lang.Boolean.toString(quiet))
    }

    val skyWalkingLoggingLevel: String
        get() {
            val skywalkingConfig = localProperties!!.getJsonObject("skywalking")
            val loggingConfig = skywalkingConfig?.getJsonObject("logging")
            val level = loggingConfig?.getString("level")
            return level?.uppercase() ?: "WARN"
        }

    private fun toProperties(config: Map<String, Map<String, Any>>?): List<Array<String>> {
        val sb: MutableList<Array<String>> = ArrayList()
        for (key in config!!.keys) {
            sb.addAll(toString(key, config[key]))
        }
        return sb
    }

    private fun toString(key: String, value: Any?): List<Array<String>> {
        val values: MutableList<Array<String>> = ArrayList()
        if (value is List<*>) {
            val lst = value as List<Any>
            for (`val` in lst) {
                if (`val` is Map<*, *> || `val` is List<*>) {
                    values.addAll(toString(key, `val`))
                } else if (`val` != null) {
                    values.add(arrayOf(key, `val`.toString()))
                }
            }
        } else {
            val map = value as Map<String, Any>?
            for (mapKey in map!!.keys) {
                if (map[mapKey] is Map<*, *> || map[mapKey] is List<*>) {
                    values.addAll(toString("$key.$mapKey", map[mapKey]))
                } else {
                    values.add(arrayOf("$key.$mapKey", map[mapKey].toString()))
                }
            }
        }
        return values
    }
}
