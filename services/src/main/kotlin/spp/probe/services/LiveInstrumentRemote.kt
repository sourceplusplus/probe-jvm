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
package spp.probe.services

import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import spp.probe.ProbeConfiguration
import spp.probe.ProbeConfiguration.PROBE_ID
import spp.probe.remotes.ILiveInstrumentRemote
import spp.probe.services.common.ContextReceiver
import spp.probe.services.instrument.LiveInstrumentService
import spp.protocol.instrument.*
import spp.protocol.instrument.command.CommandType
import spp.protocol.instrument.command.LiveInstrumentCommand
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.ProcessorAddress
import java.lang.reflect.InvocationTargetException
import java.util.*
import java.util.function.BiConsumer

@Suppress("unused")
class LiveInstrumentRemote : ILiveInstrumentRemote() {

    override fun start() {
        LiveInstrumentService.setInstrumentEventConsumer(EVENT_CONSUMER)
        vertx.eventBus()
            .localConsumer<JsonObject>(ProbeAddress.LIVE_INSTRUMENT_REMOTE + ":" + PROBE_ID)
            .handler { handleInstrumentationRequest(it) }
    }

    override fun isInstrumentEnabled(instrumentId: String): Boolean {
        return try {
            LiveInstrumentService.isInstrumentEnabled(instrumentId)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun isHit(breakpointId: String): Boolean {
        return try {
            LiveInstrumentService.isHit(breakpointId)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun putBreakpoint(breakpointId: String, source: String, line: Int, ex: Throwable) {
        try {
            ContextReceiver.putBreakpoint(breakpointId, source, line, ex)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun putLog(logId: String, logFormat: String, vararg logArguments: String?) {
        try {
            ContextReceiver.putLog(logId, logFormat, *logArguments)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun putMeter(meterId: String) {
        try {
            ContextReceiver.putMeter(meterId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun openLocalSpan(spanId: String) {
        try {
            ContextReceiver.openLocalSpan(spanId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun closeLocalSpan(spanId: String) {
        try {
            ContextReceiver.closeLocalSpan(spanId, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun closeLocalSpanAndThrowException(throwable: Throwable, spanId: String): Throwable {
        try {
            ContextReceiver.closeLocalSpan(spanId, throwable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        throw throwable
    }

    override fun putLocalVariable(breakpointId: String, key: String, value: Any?, type: String) {
        try {
            ContextReceiver.putLocalVariable(breakpointId, key, value, type)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun putField(breakpointId: String, key: String, value: Any?, type: String?) {
        try {
            ContextReceiver.putField(breakpointId, key, value, type!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun putStaticField(breakpointId: String, key: String, value: Any?, type: String) {
        try {
            ContextReceiver.putStaticField(breakpointId, key, value, type)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            ProbeConfiguration.probeMessageHeaders,
            false,
            JsonObject.mapFrom(map),
            ProbeConfiguration.tcpSocket
        )
    }

    private fun addInstrument(command: LiveInstrumentCommand) {
        if (ProbeConfiguration.isNotQuite) println("Adding instrument: $command")
        LiveInstrumentService.applyInstrument(command.instruments.first()) //todo: check for multiple
    }

    private fun removeInstrument(command: LiveInstrumentCommand) {
        for (breakpoint in command.instruments) {
            val breakpointId = breakpoint.id
            val location = breakpoint.location
            LiveInstrumentService.removeInstrument(location.source, location.line, breakpointId)
        }
        for (location in command.locations) {
            LiveInstrumentService.removeInstrument(location.source, location.line, null)
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
                ProbeConfiguration.probeMessageHeaders,
                false,
                JsonObject(json),
                ProbeConfiguration.tcpSocket
            )
        })
    }
}
