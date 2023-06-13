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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetSocket
import org.apache.commons.text.StringSubstitutor
import org.apache.commons.text.lookup.StringLookupFactory
import java.io.File
import java.io.FileInputStream
import java.lang.instrument.Instrumentation
import java.util.*
import java.util.stream.Collectors
import kotlin.system.exitProcess

object ProbeConfiguration {

    val jvmMajorVersion: Int
        get() {
            var version = System.getProperty("java.version").substringBefore("-")
            if (version.startsWith("1.")) {
                version = version.substring(2, 3)
            } else {
                version = version.substringBefore(".")
            }
            return version.toInt()
        }

    val PROBE_DIRECTORY by lazy {
        getString("install_directory")?.let { File(it) }
            ?: File(System.getProperty("java.io.tmpdir"), "spp-probe")
    }

    val PROBE_ID by lazy {
        spp.getString("probe_id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    }
    var instrumentation: Instrumentation? = null
    val probeMessageHeaders = JsonObject()

    var tcpSocket: NetSocket? = null

    var localProperties: JsonObject? = null
    var customProbeFile: String? = null

    fun load() {
        localProperties = loadConfigProperties(customProbeFile).apply {
            //ensure defaults
            if (getJsonObject("spp") == null) {
                put("spp", JsonObject())
            }
            if (getJsonObject("spp").getValue("platform_port") == null) {
                getJsonObject("spp").put("platform_port", 12800)
            }

            //provide optional formats
            val platformHost = getJsonObject("spp").getString("platform_host")
            if (platformHost?.startsWith("http") == true) {
                getJsonObject("spp").put("ssl_enabled", platformHost.startsWith("https"))

                if (platformHost.substringAfter("://").contains(":")) {
                    getJsonObject("spp").put("platform_port", platformHost.substringAfter("://").substringAfter(":"))
                    getJsonObject("spp").put("platform_host", platformHost.substringAfter("://").substringBefore(":"))
                } else {
                    getJsonObject("spp").put("platform_host", platformHost.substringAfter("://"))
                }
            }
        }
    }

    internal fun loadConfigProperties(customProbeFile: String?): JsonObject {
        var rawProperties: Map<String, Map<String, Any>>? = null
        var localFile = File("spp-probe.yml")
        customProbeFile?.let { localFile = File(it) }
        try {
            //working directory?
            val mapper = ObjectMapper(YAMLFactory())
            if (localFile.exists()) {
                rawProperties = mapper.readValue(FileInputStream(localFile), MutableMap::class.java)
                        as Map<String, Map<String, Any>>?
            }
            if (rawProperties == null && ProbeConfiguration::class.java.protectionDomain.codeSource.location == null) {
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
            } else if (rawProperties == null) {
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

            return JsonObject(
                StringSubstitutor(StringLookupFactory.INSTANCE.environmentVariableStringLookup())
                    .replace(JsonObject.mapFrom(rawProperties).toString())
            )
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
    val variableControl: JsonObject by lazy {
        val value = spp.getJsonObject("live_variable_control") ?: JsonObject()
        if (!value.containsKey("max_object_depth")) {
            value.put("max_object_depth", 5)
        }
        if (!value.containsKey("max_object_size")) {
            value.put("max_object_size", 1024L * 1024L) //1MB
        }
        if (!value.containsKey("max_collection_length")) {
            value.put("max_collection_length", 100)
        }
        return@lazy value
    }
    val variableControlByName: MutableMap<String, JsonObject> by lazy {
        val map = HashMap<String, JsonObject>()
        val list = variableControl.getJsonArray("by_variable_name")
        if (list != null) {
            for (i in 0 until list.size()) {
                val obj = list.getJsonObject(i)
                map[obj.getString("name")] = obj
            }
        }
        return@lazy map
    }
    val variableControlByType: MutableMap<String, JsonObject> by lazy {
        val map = HashMap<String, JsonObject>()
        val list = variableControl.getJsonArray("by_variable_type")
        if (list != null) {
            for (i in 0 until list.size()) {
                val obj = list.getJsonObject(i)
                map[obj.getString("type")] = obj
            }
        }
        return@lazy map
    }

    fun getJsonObject(property: String): JsonObject? {
        return localProperties!!.getJsonObject("spp").getJsonObject(property)
    }

    fun getString(property: String): String? {
        return localProperties!!.getJsonObject("spp").getString(property)
    }

    fun getInteger(property: String): Int {
        return localProperties!!.getJsonObject("spp").getValue(property).toString().toInt()
    }

    val skywalkingSettings: List<Array<String>>
        get() = toSkyWalkingSettings(localProperties!!)

    internal fun toSkyWalkingSettings(localProperties: JsonObject): MutableList<Array<String>> {
        val settings = toProperties(localProperties.map).stream()
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
            },
            getJsonObject("authentication")?.let {
                val clientId = it.getString("client_id")
                val clientSecret = it.getString("client_secret")
                val tenantId = it.getString("tenant_id")
                val clientAuth = "$clientId:$clientSecret".let {
                    if (tenantId != null) {
                        "$it:$tenantId"
                    } else {
                        it
                    }
                }
                arrayOf("skywalking.agent.authentication", clientAuth)
            }
        ).filterNotNull().toMutableSet()
    }

    val sppSettings: List<Array<String>>
        get() = toProperties(localProperties!!.map).stream()
            .filter { it[0].startsWith("spp.") }
            .collect(Collectors.toList())
    val isNotQuiet: Boolean
        get() {
            val quiteMode = getString("quiet_mode") ?: return false
            return "false".equals(quiteMode, ignoreCase = true)
        }

    val skyWalkingLoggingLevel: String
        get() {
            val skywalkingConfig = localProperties!!.getJsonObject("skywalking")
            val loggingConfig = skywalkingConfig?.getJsonObject("logging")
            val level = loggingConfig?.getString("level")
            return level?.uppercase() ?: "WARN"
        }

    val sslEnabled: Boolean
        get() {
            return spp.getValue("ssl_enabled")?.toString().toBoolean()
        }

    val waitForPlatform: Boolean
        get() {
            return spp.getValue("wait_for_platform", "true").toString().toBoolean()
        }

    private fun toProperties(config: Map<String, Any>?): List<Array<String>> {
        val sb: MutableList<Array<String>> = ArrayList()
        for (key in config!!.keys) {
            sb.addAll(toString(key, config[key]))
        }
        return sb
    }

    private fun toString(key: String, value: Any?): List<Array<String>> {
        val values: MutableList<Array<String>> = ArrayList()
        if (value is List<*>) {
            val lst = value as List<Any?>
            for (`val` in lst) {
                if (`val` is Map<*, *> || `val` is List<*>) {
                    values.addAll(toString(key, `val`))
                } else if (`val` != null) {
                    values.add(arrayOf(key, `val`.toString()))
                }
            }
        } else {
            val map = value as Map<*, *>?
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
