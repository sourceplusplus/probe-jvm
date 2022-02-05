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
package spp.probe.control

import io.vertx.core.AbstractVerticle
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.apache.skywalking.apm.agent.core.plugin.WitnessFinder
import spp.probe.ProbeConfiguration
import spp.probe.SourceProbe
import spp.protocol.ProtocolMarshaller
import spp.protocol.instrument.*
import spp.protocol.instrument.command.CommandType
import spp.protocol.instrument.command.LiveInstrumentCommand
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.ProbeAddress
import java.lang.instrument.Instrumentation
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.function.BiConsumer

class LiveInstrumentRemote : AbstractVerticle() {

    private var applyInstrument: Method? = null
    private var removeInstrument: Method? = null

    override fun start() {
        try {
            val agentClassLoader = Class.forName("org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader")
                .getMethod("getDefault").invoke(null) as ClassLoader
            val serviceClass = Class.forName(
                "spp.probe.services.instrument.LiveInstrumentService", false, agentClassLoader
            )
            val poolMapField = WitnessFinder::class.java.getDeclaredField("poolMap")
            poolMapField.isAccessible = true
            serviceClass.getMethod("setPoolMap", MutableMap::class.java)
                .invoke(null, poolMapField[WitnessFinder.INSTANCE])
            serviceClass.getMethod("setInstrumentEventConsumer", BiConsumer::class.java).invoke(null, EVENT_CONSUMER)
            serviceClass.getMethod("setInstrumentation", Instrumentation::class.java)
                .invoke(null, SourceProbe.instrumentation)
            applyInstrument = serviceClass.getMethod("applyInstrument", LiveInstrument::class.java)
            removeInstrument = serviceClass.getMethod(
                "removeInstrument",
                String::class.java, Int::class.javaPrimitiveType, String::class.java
            )
            isInstrumentEnabled = serviceClass.getMethod("isInstrumentEnabled", String::class.java)
            isHit = serviceClass.getMethod("isHit", String::class.java)
            val contextClass = Class.forName(
                "spp.probe.services.common.ContextReceiver", false, agentClassLoader
            )
            putLocalVariable = contextClass.getMethod(
                "putLocalVariable",
                String::class.java, String::class.java, Any::class.java
            )
            putField = contextClass.getMethod(
                "putField",
                String::class.java, String::class.java, Any::class.java
            )
            putStaticField = contextClass.getMethod(
                "putStaticField",
                String::class.java, String::class.java, Any::class.java
            )
            putBreakpoint = contextClass.getMethod(
                "putBreakpoint",
                String::class.java, String::class.java, Int::class.javaPrimitiveType, Throwable::class.java
            )
            putLog = contextClass.getMethod(
                "putLog",
                String::class.java, String::class.java, Array<String>::class.java
            )
            putMeter = contextClass.getMethod("putMeter", String::class.java)
            openLocalSpan = contextClass.getMethod("openLocalSpan", String::class.java)
            closeLocalSpan = contextClass.getMethod("closeLocalSpan", String::class.java, Throwable::class.java)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw RuntimeException(e)
        }

        vertx.eventBus() //global instrument remote
            .localConsumer<JsonObject>(ProbeAddress.LIVE_INSTRUMENT_REMOTE)
            .handler { handleInstrumentationRequest(it) }
        vertx.eventBus() //probe specific instrument remote
            .localConsumer<JsonObject>(ProbeAddress.LIVE_INSTRUMENT_REMOTE + ":" + SourceProbe.PROBE_ID)
            .handler { handleInstrumentationRequest(it) }
    }

    private fun handleInstrumentationRequest(it: Message<JsonObject>) {
        try {
            val command = ProtocolMarshaller.deserializeLiveInstrumentCommand(it.body())
            when (command.commandType) {
                CommandType.ADD_LIVE_INSTRUMENT -> addInstrument(command)
                CommandType.REMOVE_LIVE_INSTRUMENT -> removeInstrument(command)
            }
        } catch (ex: InvocationTargetException) {
            if (ex.cause != null) {
                publishCommandError(it, ex.cause!!)
            } else {
                publishCommandError(it, ex.targetException)
            }
        } catch (ex: Throwable) {
            publishCommandError(it, ex)
        }
    }

    private fun publishCommandError(it: Message<JsonObject>, ex: Throwable) {
        val map: MutableMap<String, Any> = HashMap()
        map["command"] = it.body().toString()
        map["occurredAt"] = System.currentTimeMillis()
        map["cause"] = ThrowableTransformer.INSTANCE.convert2String(ex, 4000)

        FrameHelper.sendFrame(
            BridgeEventType.PUBLISH.name.lowercase(), PlatformAddress.LIVE_INSTRUMENT_REMOVED,
            JsonObject.mapFrom(map), SourceProbe.tcpSocket
        )
    }

    private fun addInstrument(command: LiveInstrumentCommand) {
        if (ProbeConfiguration.isNotQuite) println("Adding instrument: $command")
        applyInstrument!!.invoke(null, command.context.instruments.first()) //todo: check for multiple
    }

    private fun removeInstrument(command: LiveInstrumentCommand) {
        for (breakpoint in command.context.instruments) {
            val breakpointId = breakpoint.id
            val location = breakpoint.location
            removeInstrument!!.invoke(null, location.source, location.line, breakpointId)
        }
        for (location in command.context.locations) {
            removeInstrument!!.invoke(null, location.source, location.line, null)
        }
    }

    companion object {
        private val EVENT_CONSUMER = BiConsumer(fun(address: String?, json: String?) {
            if (ProbeConfiguration.isNotQuite) println("Publishing event: $address, $json")
            FrameHelper.sendFrame(
                BridgeEventType.PUBLISH.name.lowercase(),
                address,
                JsonObject(json),
                SourceProbe.tcpSocket
            )
        })
        private var putBreakpoint: Method? = null
        private var putLog: Method? = null
        private var putMeter: Method? = null
        private var openLocalSpan: Method? = null
        private var closeLocalSpan: Method? = null
        private var isInstrumentEnabled: Method? = null
        private var putLocalVariable: Method? = null
        private var putField: Method? = null
        private var putStaticField: Method? = null
        private var isHit: Method? = null

        @JvmStatic
        fun isInstrumentEnabled(instrumentId: String): Boolean {
            return try {
                isInstrumentEnabled!!.invoke(null, instrumentId) as Boolean
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        @JvmStatic
        fun isHit(breakpointId: String): Boolean {
            return try {
                isHit!!.invoke(null, breakpointId) as Boolean
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        @JvmStatic
        fun putBreakpoint(breakpointId: String, source: String, line: Int, ex: Throwable?) {
            try {
                putBreakpoint!!.invoke(null, breakpointId, source, line, ex)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun putLog(logId: String, logFormat: String, vararg logArguments: String?) {
            try {
                putLog!!.invoke(null, logId, logFormat, logArguments)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun putMeter(meterId: String) {
            try {
                putMeter!!.invoke(null, meterId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun openLocalSpan(spanId: String) {
            try {
                openLocalSpan!!.invoke(null, spanId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun closeLocalSpan(spanId: String) {
            try {
                closeLocalSpan!!.invoke(null, spanId, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun closeLocalSpanAndThrowException(throwable: Throwable, spanId: String): Throwable {
            try {
                closeLocalSpan!!.invoke(null, spanId, throwable)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            throw throwable
        }

        @JvmStatic
        fun putLocalVariable(breakpointId: String, key: String, value: Any?) {
            try {
                putLocalVariable!!.invoke(null, breakpointId, key, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun putField(breakpointId: String, key: String, value: Any?) {
            try {
                putField!!.invoke(null, breakpointId, key, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun putStaticField(breakpointId: String, key: String, value: Any?) {
            try {
                putStaticField!!.invoke(null, breakpointId, key, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
