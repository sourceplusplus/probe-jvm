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
import spp.probe.services.common.ProbeMemory
import spp.probe.services.instrument.LiveInstrumentService
import spp.protocol.instrument.command.CommandType
import spp.protocol.instrument.command.LiveInstrumentCommand
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.ProcessorAddress
import java.util.function.BiConsumer

@Suppress("unused", "TooManyFunctions", "CyclomaticComplexMethod")
class LiveInstrumentRemote : ILiveInstrumentRemote() {

    companion object {
        private val log = LogManager.getLogger(LiveInstrumentRemote::class.java)

        var EVENT_CONSUMER = BiConsumer(fun(address: String?, json: String?) {
            if (log.isTraceEnabled) log.trace("Publishing event: $address, $json")
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

    override fun start() {
        if (log.isTraceEnabled) log.trace("Starting LiveInstrumentRemote")
        vertx.eventBus()
            .localConsumer<JsonObject>(ProbeAddress.LIVE_INSTRUMENT_REMOTE + ":" + PROBE_ID)
            .handler { handleInstrumentationRequest(it) }
    }

    override fun registerRemote() {
        FrameHelper.sendFrame(
            BridgeEventType.REGISTER.name.lowercase(),
            ProbeAddress.LIVE_INSTRUMENT_REMOTE + ":" + PROBE_ID,
            null,
            ProbeConfiguration.probeMessageHeaders,
            false,
            JsonObject(),
            ProbeConfiguration.tcpSocket
        )
    }

    override fun isInstrumentEnabled(instrumentId: String): Boolean {
        return try {
            LiveInstrumentService.isInstrumentEnabled(instrumentId)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(instrumentId, t)
            false
        }
    }

    override fun isHit(instrumentId: String): Boolean {
        return try {
            LiveInstrumentService.isHit(instrumentId)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(instrumentId, t)
            false
        }
    }

    override fun putBreakpoint(breakpointId: String, ex: Throwable) {
        try {
            ContextReceiver.putBreakpoint(breakpointId, ex)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(breakpointId, t)
        }
    }

    override fun putLog(logId: String, logFormat: String, vararg logArguments: String?) {
        try {
            ContextReceiver.putLog(logId, logFormat, *logArguments)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(logId, t)
        }
    }

    override fun putMeter(meterId: String) {
        try {
            ContextReceiver.putMeter(meterId)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(meterId, t)
        }
    }

    override fun openLocalSpan(spanId: String) {
        try {
            ContextReceiver.openLocalSpan(spanId)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(spanId, t)
        }
    }

    override fun closeLocalSpan(spanId: String) {
        try {
            ContextReceiver.closeLocalSpan(spanId, null)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(spanId, t)
        }
    }

    override fun closeLocalSpanAndThrowException(throwable: Throwable, spanId: String): Throwable {
        try {
            ContextReceiver.closeLocalSpan(spanId, throwable)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(spanId, t)
        }
        throw throwable
    }

    override fun putContext(instrumentId: String, key: String, value: Any) {
        ProbeMemory.putContextVariable(instrumentId, key, Triple(value::class.java.name, value, -1))
    }

    override fun putLocalVariable(instrumentId: String, key: String, value: Any?, type: String, line: Int) {
        ProbeMemory.putLocalVariable(instrumentId, key, Triple(type, value, line))
    }

    override fun putField(instrumentId: String, key: String, value: Any?, type: String, line: Int) {
        ProbeMemory.putFieldVariable(instrumentId, key, Triple(type, value, line))
    }

    override fun putStaticField(instrumentId: String, key: String, value: Any?, type: String, line: Int) {
        ProbeMemory.putStaticVariable(instrumentId, key, Triple(type, value, line))
    }

    override fun putReturn(instrumentId: String, value: Any?, type: String) {
        ProbeMemory.putLocalVariable(instrumentId, "@return", Triple(type, value, -1))
    }

    override fun startTimer(meterId: String) {
        try {
            ContextReceiver.startTimer(meterId)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(meterId, t)
        }
    }

    override fun stopTimer(meterId: String) {
        try {
            ContextReceiver.stopTimer(meterId)
        } catch (t: Throwable) {
            LiveInstrumentService.removeInstrument(meterId, t)
        }
    }

    private fun handleInstrumentationRequest(it: Message<JsonObject>) {
        try {
            val command = LiveInstrumentCommand(it.body())
            if (log.isInfoEnable) log.info("Received command: $command")

            when (command.commandType) {
                CommandType.ADD_LIVE_INSTRUMENT -> addInstruments(command)
                CommandType.REMOVE_LIVE_INSTRUMENT -> removeInstruments(command)
                CommandType.SET_INITIAL_INSTRUMENTS -> {
                    try {
                        addInstruments(command)
                    } finally {
                        vertx.eventBus().publish(INITIAL_INSTRUMENTS_SET, JsonObject())
                    }
                }
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

    private fun addInstruments(command: LiveInstrumentCommand) {
        command.instruments.forEach {
            if (log.isInfoEnable) log.info("Adding instrument: $it")
            LiveInstrumentService.applyInstrument(it)
        }
    }

    private fun removeInstruments(command: LiveInstrumentCommand) {
        for (breakpoint in command.instruments) {
            if (log.isInfoEnable) log.info("Removing instrument: $breakpoint")
            val breakpointId = breakpoint.id
            val location = breakpoint.location
            LiveInstrumentService.removeInstrument(location.source, location.line, breakpointId)
        }
        for (location in command.locations) {
            if (log.isInfoEnable) log.info("Removing instrument: $location")
            LiveInstrumentService.removeInstrument(location.source, location.line, null)
        }
    }
}
