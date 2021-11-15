package spp.probe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.vertx.core.json.JsonObject
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
            //working directory
            val mapper = ObjectMapper(YAMLFactory())
            if (localFile.exists()) {
                rawProperties = mapper.readValue(FileInputStream(localFile), MutableMap::class.java)
                        as Map<String, Map<String, Any>>?
            }
            //ran through intellij
            localFile = File(
                File(
                    ProbeConfiguration::class.java.protectionDomain.codeSource.location.toURI()
                ).parent, "spp-probe.yml"
            )
            if (localFile.exists()) {
                rawProperties = mapper.readValue(FileInputStream(localFile), MutableMap::class.java)
                        as Map<String, Map<String, Any>>?
            }
            //inside jar
            if (rawProperties == null) {
                rawProperties = mapper.readValue(
                    ProbeConfiguration::class.java.getResourceAsStream("/spp-probe.yml"), MutableMap::class.java
                ) as Map<String, Map<String, Any>>?
            }
            localProperties = JsonObject.mapFrom(rawProperties)
        } catch (e: Exception) {
            System.err.println("Failed to read properties file: " + localFile)
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
    fun setString(property: String?, value: String?) {
        localProperties!!.getJsonObject("spp").put(property, value)
    }

    fun getInteger(property: String?): Int {
        return localProperties!!.getJsonObject("spp").getInteger(property)
    }

    fun setInteger(property: String?, value: Int) {
        localProperties!!.getJsonObject("spp").put(property, value)
    }

    val skywalkingSettings: List<Array<String>>
        get() {
            val settings = toProperties(rawProperties).stream()
                .filter { it: Array<String> -> it[0].startsWith("skywalking.") }
                .collect(Collectors.toList())
            if (settings.stream().noneMatch { it: Array<String> -> it[0] == "skywalking.agent.service_name" } ||
                settings.stream().noneMatch { it: Array<String> -> it[0] == "skywalking.collector.backend_service" }) {
                throw RuntimeException("Missing Apache SkyWalking setup configuration")
            }
            return settings
        }
    val sppSettings: List<Array<String>>
        get() = toProperties(rawProperties).stream()
            .filter { it: Array<String> -> it[0].startsWith("spp.") }
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