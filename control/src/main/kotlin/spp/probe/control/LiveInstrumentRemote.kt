package spp.probe.control

import io.vertx.core.AbstractVerticle
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.apache.skywalking.apm.agent.core.plugin.WitnessFinder
import spp.probe.ProbeConfiguration
import spp.probe.SourceProbe
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.log.LiveLog
import spp.protocol.instrument.meter.LiveMeter
import spp.protocol.instrument.span.LiveSpan
import spp.protocol.platform.PlatformAddress
import spp.protocol.probe.ProbeAddress
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import java.lang.instrument.Instrumentation
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.function.BiConsumer
import kotlin.reflect.KClass

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
        vertx.eventBus()
            .localConsumer<JsonObject>("local." + ProbeAddress.LIVE_BREAKPOINT_REMOTE.address + ":" + SourceProbe.PROBE_ID)
            .handler { handleInstrumentationRequest(LiveBreakpoint::class, it) }
        vertx.eventBus()
            .localConsumer<JsonObject>("local." + ProbeAddress.LIVE_LOG_REMOTE.address + ":" + SourceProbe.PROBE_ID)
            .handler { handleInstrumentationRequest(LiveLog::class, it) }
        vertx.eventBus()
            .localConsumer<JsonObject>("local." + ProbeAddress.LIVE_METER_REMOTE.address + ":" + SourceProbe.PROBE_ID)
            .handler { handleInstrumentationRequest(LiveMeter::class, it) }
        vertx.eventBus()
            .localConsumer<JsonObject>("local." + ProbeAddress.LIVE_SPAN_REMOTE.address + ":" + SourceProbe.PROBE_ID)
            .handler { handleInstrumentationRequest(LiveSpan::class, it) }
    }

    private fun handleInstrumentationRequest(clazz: KClass<out LiveInstrument>, it: Message<JsonObject>) {
        try {
            val command = Json.decodeValue(it.body().toString(), LiveInstrumentCommand::class.java)
            when (command.commandType) {
                CommandType.ADD_LIVE_INSTRUMENT -> addInstrument(clazz, command)
                CommandType.REMOVE_LIVE_INSTRUMENT -> removeInstrument(command)
            }
        } catch (ex: InvocationTargetException) {
            val map: MutableMap<String, Any> = HashMap()
            map["command"] = it.body().toString()
            map["occurredAt"] = System.currentTimeMillis()
            if (ex.cause != null) {
                map["cause"] = ThrowableTransformer.INSTANCE.convert2String(ex.cause, 4000)
            } else {
                map["cause"] = ThrowableTransformer.INSTANCE.convert2String(ex.targetException, 4000)
            }

            val address = when (clazz) {
                LiveBreakpoint::class -> PlatformAddress.LIVE_BREAKPOINT_REMOVED.address
                LiveLog::class -> PlatformAddress.LIVE_LOG_REMOVED.address
                LiveMeter::class -> PlatformAddress.LIVE_METER_REMOVED.address
                LiveSpan::class -> PlatformAddress.LIVE_SPAN_REMOVED.address
                else -> throw IllegalArgumentException("Unknown instrument: $clazz")
            }
            FrameHelper.sendFrame(
                BridgeEventType.PUBLISH.name.lowercase(), address, JsonObject.mapFrom(map), SourceProbe.tcpSocket
            )
        } catch (ex: Throwable) {
            val map: MutableMap<String, Any> = HashMap()
            map["command"] = it.body().toString()
            map["occurredAt"] = System.currentTimeMillis()
            map["cause"] = ThrowableTransformer.INSTANCE.convert2String(ex, 4000)

            val address = when (clazz) {
                LiveBreakpoint::class -> PlatformAddress.LIVE_BREAKPOINT_REMOVED.address
                LiveLog::class -> PlatformAddress.LIVE_LOG_REMOVED.address
                LiveMeter::class -> PlatformAddress.LIVE_METER_REMOVED.address
                LiveSpan::class -> PlatformAddress.LIVE_SPAN_REMOVED.address
                else -> throw IllegalArgumentException("Unknown instrument: $clazz")
            }
            FrameHelper.sendFrame(
                BridgeEventType.PUBLISH.name.lowercase(), address, JsonObject.mapFrom(map), SourceProbe.tcpSocket
            )
        }
    }

    private fun addInstrument(clazz: KClass<out LiveInstrument>, command: LiveInstrumentCommand) {
        if (ProbeConfiguration.isNotQuite) println("Adding instrument: $command")
        val instrumentData = command.context.liveInstruments[0]
        applyInstrument!!.invoke(null, Json.decodeValue(instrumentData, clazz.java))
    }

    private fun removeInstrument(command: LiveInstrumentCommand) {
        for (breakpointData in command.context.liveInstruments) {
            val breakpointObject = JsonObject(breakpointData)
            val breakpointId = breakpointObject.getString("id")
            val location = breakpointObject.getJsonObject("location")
            val source = location.getString("source")
            val line = location.getInteger("line")
            removeInstrument!!.invoke(null, source, line, breakpointId)
        }
        for (locationData in command.context.locations) {
            val location = JsonObject(locationData)
            val source = location.getString("source")
            val line = location.getInteger("line")
            removeInstrument!!.invoke(null, source, line, null)
        }
    }

    companion object {
        private val EVENT_CONSUMER = BiConsumer(fun(address: String?, json: String?) {
            if (ProbeConfiguration.isNotQuite) println("Publishing event: $address, $json")
            FrameHelper.sendFrame(
                BridgeEventType.PUBLISH.name.lowercase(Locale.getDefault()),
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
