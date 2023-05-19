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
package spp.probe.services.instrument

import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import org.springframework.expression.ParseException
import org.springframework.expression.spel.SpelCompilerMode
import org.springframework.expression.spel.SpelParserConfiguration
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.SimpleEvaluationContext
import spp.probe.ProbeConfiguration
import spp.probe.services.LiveInstrumentRemote
import spp.probe.services.common.ContextReceiver
import spp.probe.services.common.ModelSerializer
import spp.probe.services.common.ProbeMemory
import spp.probe.services.common.model.ActiveLiveInstrument
import spp.probe.services.common.transform.LiveTransformer
import spp.probe.services.error.LiveInstrumentException
import spp.protocol.instrument.LiveInstrument
import spp.protocol.platform.ProcessorAddress
import java.lang.instrument.Instrumentation
import java.lang.instrument.UnmodifiableClassException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.toList

object LiveInstrumentService {

    val parser = SpelExpressionParser(
        SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveInstrumentService::class.java.classLoader)
    )

    private val log = LogManager.getLogger(LiveInstrumentService::class.java)
    private val instruments: MutableMap<String, ActiveLiveInstrument> = ConcurrentHashMap()
    private val timer = Timer("LiveInstrumentScheduler", true)
    internal val instrumentsMap: Map<String, ActiveLiveInstrument>
        get() = HashMap(instruments)
    private val transformer = LiveTransformer()

    init {
        ProbeConfiguration.instrumentation!!.addTransformer(transformer, true)

        timer.schedule(object : TimerTask() {
            override fun run() {
                if (log.isDebugEnable) log.debug("Running LiveInstrumentScheduler")
                val removeInstruments: MutableList<ActiveLiveInstrument> = ArrayList()
                instruments.values.forEach {
                    if (it.instrument.expiresAt != null
                        && System.currentTimeMillis() >= it.instrument.expiresAt!!
                    ) {
                        removeInstruments.add(it)
                    }
                }

                if (log.isDebugEnable) log.debug("Found {} expired instruments", removeInstruments.size)
                removeInstruments.forEach { removeInstrument(it.instrument, null) }
            }
        }, 5000, 5000)
    }

    var liveInstrumentApplier = object : LiveInstrumentApplier {
        override fun apply(inst: Instrumentation, instrument: ActiveLiveInstrument) {
            if (log.isInfoEnable) {
                if (instrument.isRemoval) {
                    log.info("Attempting to remove live instrument: {}", instrument.instrument.id)
                } else {
                    log.info("Attempting to apply live instrument: {}", instrument.instrument.id)
                }
            }

            val className = if (instrument.instrument.location.source.contains("(")) {
                instrument.instrument.location.source.substringBefore("(").substringBeforeLast(".")
            } else {
                instrument.instrument.location.source
            }

            if (log.isInfoEnable) log.info("Searching for {} in all loaded classes", className)
            val clazz: Class<*>? = inst.allLoadedClasses.find { it.name == className }
            if (clazz != null) {
                if (log.isInfoEnable) log.info("Found {} in all loaded classes", clazz)
            }

            if (clazz == null) {
                if (log.isDebugEnable) log.debug("{} not found", className)
                if (instrument.instrument.applyImmediately) {
                    log.warn(
                        "Unable to find {}. Live instrument {} cannot be applied immediately",
                        className, instrument.instrument
                    )
                    throw LiveInstrumentException(LiveInstrumentException.ErrorType.CLASS_NOT_FOUND, className)
                        .toEventBusException()
                }
                return
            }

            try {
                inst.retransformClasses(clazz)
                var innerClazz = transformer.innerClasses.poll()
                while (innerClazz != null) {
                    inst.retransformClasses(innerClazz)
                    innerClazz = transformer.innerClasses.poll()
                }
                instrument.isLive = true

                if (instrument.isRemoval) {
                    if (log.isInfoEnable) log.info("Successfully removed live instrument: {}", instrument.instrument.id)
                } else {
                    if (log.isInfoEnable) log.info("Successfully applied live instrument {}", instrument.instrument.id)
                    LiveInstrumentRemote.EVENT_CONSUMER.accept(
                        ProcessorAddress.LIVE_INSTRUMENT_APPLIED,
                        ModelSerializer.INSTANCE.toJson(instrument.instrument)
                    )
                }
            } catch (ex: Throwable) {
                log.warn(ex, "Failed to apply live instrument: {}", instrument)

                //remove and re-transform
                removeInstrument(instrument.instrument, ex)
                try {
                    inst.retransformClasses(clazz)
                } catch (e: UnmodifiableClassException) {
                    log.warn(e, "Failed to re-transform class: {}", clazz)
                    throw RuntimeException(e)
                }
            }
        }
    }

    fun clearAll() {
        instruments.clear()
    }

    fun applyInstrument(liveInstrument: LiveInstrument): String {
        val existingInstrument = instruments[liveInstrument.id]
        return if (existingInstrument != null) {
            ModelSerializer.INSTANCE.toJson(existingInstrument.instrument)
        } else {
            val activeInstrument = if (liveInstrument.condition?.isNotEmpty() == true) {
                try {
                    val expression = parser.parseExpression(liveInstrument.condition!!)
                    ActiveLiveInstrument(liveInstrument, expression)
                } catch (ex: ParseException) {
                    log.warn(ex, "Failed to parse condition: {}", liveInstrument.condition)
                    throw LiveInstrumentException(LiveInstrumentException.ErrorType.CONDITIONAL_FAILED, ex.message)
                        .toEventBusException()
                }
            } else {
                ActiveLiveInstrument(liveInstrument)
            }

            instruments[liveInstrument.id!!] = activeInstrument
            try {
                liveInstrumentApplier.apply(ProbeConfiguration.instrumentation!!, activeInstrument)
            } catch (ex: Throwable) {
                instruments.remove(liveInstrument.id)
                throw ex
            }

            ModelSerializer.INSTANCE.toJson(activeInstrument.instrument)
        }
    }

    fun removeInstrument(source: String?, line: Int, instrumentId: String?): Collection<String> {
        if (instrumentId != null) {
            val removedInstrument = instruments.remove(instrumentId)
            if (removedInstrument != null) {
                removedInstrument.isRemoval = true
                if (removedInstrument.isLive) {
                    liveInstrumentApplier.apply(ProbeConfiguration.instrumentation!!, removedInstrument)
                    return listOf(ModelSerializer.INSTANCE.toJson(removedInstrument.instrument))
                }
            }
        } else {
            val removedInstruments: MutableList<String> = ArrayList()
            getInstruments(source!!, line).forEach {
                val removedInstrument = instruments.remove(it.instrument.id)
                if (removedInstrument != null) {
                    removedInstrument.isRemoval = true
                    if (removedInstrument.isLive) {
                        liveInstrumentApplier.apply(ProbeConfiguration.instrumentation!!, removedInstrument)
                        removedInstruments.add(ModelSerializer.INSTANCE.toJson(removedInstrument.instrument))
                    }
                }
            }
            return removedInstruments
        }
        return emptyList()
    }

    fun removeInstrument(instrumentId: String, ex: Throwable) {
        val removedInstrument = instruments.remove(instrumentId)
        if (removedInstrument != null) {
            removedInstrument.isRemoval = true
            if (removedInstrument.isLive) {
                liveInstrumentApplier.apply(ProbeConfiguration.instrumentation!!, removedInstrument)
            }
            removeInstrument(removedInstrument.instrument, ex)
        } else {
            log.warn(ex, "Unable to find instrument: {}", instrumentId)
        }
    }

    private fun removeInstrument(instrument: LiveInstrument, ex: Throwable?) {
        if (ex != null) {
            log.warn(ex, "Removing erroneous live instrument: {}", instrument.id)
        } else {
            log.info("Removing live instrument: {}", instrument.id)
        }

        removeInstrument(instrument.location.source, instrument.location.line, instrument.id)
        val map: MutableMap<String, Any?> = HashMap()
        map["instrument"] = ModelSerializer.INSTANCE.toJson(instrument)
        map["occurredAt"] = System.currentTimeMillis()
        if (ex != null) {
            map["cause"] = ThrowableTransformer.INSTANCE.convert2String(ex, 4000)
        }

        LiveInstrumentRemote.EVENT_CONSUMER.accept(
            ProcessorAddress.LIVE_INSTRUMENT_REMOVED,
            ModelSerializer.INSTANCE.toJson(map)
        )
    }

    fun getInstrumentsForClass(source: String): List<ActiveLiveInstrument> {
        return instruments.values.stream().filter {
            var className = it.instrument.location.source
            if (className.contains("(")) {
                className = className.substringBefore("(").substringBeforeLast(".")
            }
            source.startsWith(className)
        }.toList()
    }

    fun getInstruments(source: String): List<ActiveLiveInstrument> {
        return instruments.values.stream().filter {
            it.instrument.location.source == source ||
                    (it.instrument.location.source.endsWith("(...)")
                            && source.startsWith(it.instrument.location.source.substringBefore("(...)")))
        }.toList()
    }

    fun getInstruments(source: String, line: Int): List<ActiveLiveInstrument> {
        return instruments.values.stream().filter {
            (it.instrument.location.source == source || it.instrument.location.source.startsWith(source + "\$"))
                    && it.instrument.location.line == line
        }.toList()
    }

    fun getInstrument(instrumentId: String): LiveInstrument? {
        return instruments[instrumentId]?.instrument
    }

    fun isInstrumentEnabled(instrumentId: String): Boolean {
        if (log.isTraceEnabled) log.trace("Checking if instrument is enabled: $instrumentId")
        return instruments.containsKey(instrumentId)
    }

    fun isHit(instrumentId: String): Boolean {
        if (log.isTraceEnabled) log.trace("Checking if instrument is hit: $instrumentId")
        val instrument = instruments[instrumentId] ?: return false
        if (instrument.throttle?.isRateLimited() == true) {
            if (log.isTraceEnabled) log.trace("Instrument is rate limited: $instrumentId")
            return false
        }
        if (instrument.expression == null) {
            if (instrument.isFinished) {
                if (log.isInfoEnable) log.info("Instrument is finished: {}", instrumentId)
                removeInstrument(instrument.instrument, null)
            }

            //store instrument in probe memory, removed on ContextReceiver.put
            ProbeMemory.putLocal("spp.live-instrument:$instrumentId", instrument.instrument)

            if (log.isTraceEnabled) log.trace("Instrument is hit: $instrumentId")
            return true
        }
        return try {
            if (evaluateCondition(instrument)) {
                if (instrument.isFinished) {
                    if (log.isInfoEnable) log.info("Instrument is finished: {}", instrumentId)
                    removeInstrument(instrument.instrument, null)
                }

                //store instrument in probe memory, removed on ContextReceiver.put
                ProbeMemory.putLocal("spp.live-instrument:$instrumentId", instrument.instrument)

                if (log.isTraceEnabled) log.trace("Instrument is hit: $instrumentId")
                true
            } else {
                if (log.isTraceEnabled) log.trace("Instrument is not hit: $instrumentId")
                false
            }
        } catch (e: Throwable) {
            log.warn(e, "Failed to evaluate condition: {}", instrument.instrument.condition)
            removeInstrument(instrument.instrument, e)
            false
        }
    }

    private fun evaluateCondition(liveInstrument: ActiveLiveInstrument): Boolean {
        val rootObject = ContextReceiver[liveInstrument.instrument.id!!, false]
        val context = SimpleEvaluationContext.forReadOnlyDataBinding()
            .withRootObject(rootObject).build()
        return liveInstrument.expression!!.getValue(context, Boolean::class.java) ?: false
    }
}
