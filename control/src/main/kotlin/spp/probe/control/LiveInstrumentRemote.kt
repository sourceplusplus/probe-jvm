/*
 * Source++, the open-source live coding platform.
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
package spp.probe.control

import io.vertx.core.AbstractVerticle
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import spp.probe.ProbeConfiguration
import spp.probe.SourceProbe
import spp.protocol.instrument.*
import spp.protocol.instrument.command.CommandType
import spp.protocol.instrument.command.LiveInstrumentCommand
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.ProcessorAddress
import java.lang.instrument.Instrumentation
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.function.BiConsumer

@Suppress("unused")
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
                String::class.java, String::class.java, Any::class.java, String::class.java
            )
            putField = contextClass.getMethod(
                "putField",
                String::class.java, String::class.java, Any::class.java, String::class.java
            )
            putStaticField = contextClass.getMethod(
                "putStaticField",
                String::class.java, String::class.java, Any::class.java, String::class.java
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
        } catch (ex: Throwable) {
            log.error("Failed to initialize live instrument remote", ex)
            throw RuntimeException(ex)
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
            val command = LiveInstrumentCommand(it.body())
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
        log.error("Error occurred while processing command", ex)

        FrameHelper.sendFrame(
            BridgeEventType.PUBLISH.name.lowercase(),
            ProcessorAddress.LIVE_INSTRUMENT_REMOVED,
            null,
            SourceProbe.probeMessageHeaders,
            false,
            JsonObject.mapFrom(map),
            SourceProbe.tcpSocket
        )
    }

    private fun addInstrument(command: LiveInstrumentCommand) {
        if (ProbeConfiguration.isNotQuite) println("Adding instrument: $command")
        applyInstrument!!.invoke(null, command.instruments.first()) //todo: check for multiple
    }

    private fun removeInstrument(command: LiveInstrumentCommand) {
        for (breakpoint in command.instruments) {
            val breakpointId = breakpoint.id
            val location = breakpoint.location
            removeInstrument!!.invoke(null, location.source, location.line, breakpointId)
        }
        for (location in command.locations) {
            removeInstrument!!.invoke(null, location.source, location.line, null)
        }
    }

    companion object {
        private val log = LogManager.getLogger(LiveInstrumentRemote::class.java)
        private val EVENT_CONSUMER = BiConsumer(fun(address: String?, json: String?) {
            if (ProbeConfiguration.isNotQuite) println("Publishing event: $address, $json")
            FrameHelper.sendFrame(
                BridgeEventType.PUBLISH.name.lowercase(),
                address,
                null,
                SourceProbe.probeMessageHeaders,
                false,
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
        fun putLocalVariable(breakpointId: String, key: String, value: Any?, type: String) {
            try {
                putLocalVariable!!.invoke(null, breakpointId, key, value, type)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun putField(breakpointId: String, key: String, value: Any?, type: String?) {
            try {
                putField!!.invoke(null, breakpointId, key, value, type)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun putStaticField(breakpointId: String, key: String, value: Any?, type: String) {
            try {
                putStaticField!!.invoke(null, breakpointId, key, value, type)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
