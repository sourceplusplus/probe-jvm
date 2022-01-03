package spp.probe.services.instrument

import net.bytebuddy.pool.TypePool
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.springframework.expression.ParseException
import org.springframework.expression.spel.SpelCompilerMode
import org.springframework.expression.spel.SpelParserConfiguration
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import spp.probe.services.common.ContextReceiver
import spp.probe.services.common.ModelSerializer
import spp.probe.services.common.model.ActiveLiveInstrument
import spp.probe.services.common.transform.LiveTransformer
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.log.LiveLog
import spp.protocol.instrument.meter.LiveMeter
import spp.protocol.platform.PlatformAddress
import spp.protocol.probe.error.LiveInstrumentException
import java.lang.instrument.Instrumentation
import java.lang.instrument.UnmodifiableClassException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.stream.Collectors

object LiveInstrumentService {

    private val instruments: MutableMap<String?, ActiveLiveInstrument> = ConcurrentHashMap()
    private val applyingInstruments: MutableMap<String?, ActiveLiveInstrument> = ConcurrentHashMap()
    private val parser = SpelExpressionParser(
        SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveInstrumentService::class.java.classLoader)
    )
    private var instrumentEventConsumer: BiConsumer<String, String>? = null
    private var poolMap: Map<ClassLoader, TypePool> = HashMap()
    private val timer = Timer("LiveInstrumentScheduler", true)
    private var instrumentation: Instrumentation? = null

    init {
        timer.schedule(object : TimerTask() {
            override fun run() {
                val removeInstruments: MutableList<ActiveLiveInstrument> = ArrayList()
                instruments.values.forEach(Consumer {
                    if (it.instrument.expiresAt != null
                        && System.currentTimeMillis() >= it.instrument.expiresAt!!
                    ) {
                        removeInstruments.add(it)
                    }
                })
                applyingInstruments.values.forEach(Consumer {
                    if (it.instrument.expiresAt != null
                        && System.currentTimeMillis() >= it.instrument.expiresAt!!
                    ) {
                        removeInstruments.add(it)
                    }
                })
                removeInstruments.forEach(Consumer {
                    _removeInstrument(
                        it.instrument,
                        null
                    )
                })
            }
        }, 5000, 5000)
    }

    var liveInstrumentApplier = object : LiveInstrumentApplier {
        override fun apply(inst: Instrumentation, instrument: ActiveLiveInstrument) {
            var clazz: Class<*>? = null
            for (classLoader in poolMap.keys) {
                try {
                    clazz = Class.forName(instrument.instrument.location.source, true, classLoader)
                } catch (ignored: ClassNotFoundException) {
                }
            }
            if (poolMap.isEmpty()) {
                try {
                    clazz = Class.forName(instrument.instrument.location.source)
                } catch (ignored: ClassNotFoundException) {
                }
            }
            if (clazz == null) {
                if (instrument.instrument.applyImmediately) {
                    throw LiveInstrumentException(
                        LiveInstrumentException.ErrorType.CLASS_NOT_FOUND, instrument.instrument.location.source
                    ).toEventBusException()
                } else if (!instrument.isRemoval) {
                    timer.schedule(object : TimerTask() {
                        override fun run() {
                            apply(inst, instrument)
                        }
                    }, 5000)
                }
                return
            }

            val transformer = LiveTransformer(instrument.instrument)
            try {
                if (!instrument.isRemoval) {
                    applyingInstruments[instrument.instrument.id] = instrument
                }
                inst.addTransformer(transformer, true)
                inst.retransformClasses(clazz)
                instrument.isLive = true
                if (!instrument.isRemoval) {
                    if (instrument.instrument is LiveLog) {
                        instrumentEventConsumer!!.accept(
                            PlatformAddress.LIVE_LOG_APPLIED.address,
                            ModelSerializer.INSTANCE.toJson(instrument.instrument)
                        )
                    } else if (instrument.instrument is LiveBreakpoint) {
                        instrumentEventConsumer!!.accept(
                            PlatformAddress.LIVE_BREAKPOINT_APPLIED.address,
                            ModelSerializer.INSTANCE.toJson(instrument.instrument)
                        )
                    } else if (instrument.instrument is LiveMeter) {
                        instrumentEventConsumer!!.accept(
                            PlatformAddress.LIVE_METER_APPLIED.address,
                            ModelSerializer.INSTANCE.toJson(instrument.instrument)
                        )
                    }
                    //                else if (instrument.getInstrument() instanceof LiveSpan) {
//                    instrumentEventConsumer.accept(LIVE_SPAN_APPLIED.getAddress(), ModelSerializer.INSTANCE.toJson(instrument.getInstrument()));
//                }
                }
            } catch (ex: Throwable) {
                //remove and re-transform
                _removeInstrument(instrument.instrument, ex)
                applyingInstruments.remove(instrument.instrument.id)
                inst.addTransformer(transformer, true)
                try {
                    inst.retransformClasses(clazz)
                } catch (e: UnmodifiableClassException) {
                    throw RuntimeException(e)
                }
            } finally {
                applyingInstruments.remove(instrument.instrument.id)
                inst.removeTransformer(transformer)
            }
        }
    }

    @JvmStatic
    fun setPoolMap(poolMap: Map<*, *>) {
        LiveInstrumentService.poolMap = poolMap as Map<ClassLoader, TypePool>
    }

    @JvmStatic
    fun setInstrumentEventConsumer(instrumentEventConsumer: BiConsumer<*, *>) {
        LiveInstrumentService.instrumentEventConsumer = instrumentEventConsumer as BiConsumer<String, String>
    }

    fun setInstrumentApplier(liveInstrumentApplier: LiveInstrumentApplier) {
        LiveInstrumentService.liveInstrumentApplier = liveInstrumentApplier
    }

    @JvmStatic
    fun setInstrumentation(instrumentation: Instrumentation?) {
        LiveInstrumentService.instrumentation = instrumentation
    }

    val instrumentsMap: Map<String?, ActiveLiveInstrument>
        get() = HashMap(instruments)

    fun clearAll() {
        instruments.clear()
        applyingInstruments.clear()
    }

    @JvmStatic
    fun applyInstrument(liveInstrument: LiveInstrument): String {
        var existingInstrument = applyingInstruments[liveInstrument.id]
        if (existingInstrument == null) existingInstrument = instruments[liveInstrument.id]
        return if (existingInstrument != null) {
            ModelSerializer.INSTANCE.toJson(existingInstrument.instrument)
        } else {
            val activeInstrument = if (liveInstrument.condition?.isNotEmpty() == true) {
                try {
                    val expression = parser.parseExpression(liveInstrument.condition!!)
                    ActiveLiveInstrument(liveInstrument, expression)
                } catch (ex: ParseException) {
                    throw LiveInstrumentException(LiveInstrumentException.ErrorType.CONDITIONAL_FAILED, ex.message)
                        .toEventBusException()
                }
            } else {
                ActiveLiveInstrument(liveInstrument)
            }
            liveInstrumentApplier.apply(instrumentation!!, activeInstrument)
            instruments[liveInstrument.id] = activeInstrument
            ModelSerializer.INSTANCE.toJson(activeInstrument.instrument)
        }
    }

    @JvmStatic
    fun removeInstrument(source: String?, line: Int, instrumentId: String?): Collection<String> {
        if (instrumentId != null) {
            val removedInstrument = instruments.remove(instrumentId)
            if (removedInstrument != null) {
                removedInstrument.isRemoval = true
                if (removedInstrument.isLive) {
                    liveInstrumentApplier.apply(instrumentation!!, removedInstrument)
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
                        liveInstrumentApplier.apply(instrumentation!!, removedInstrument)
                        removedInstruments.add(ModelSerializer.INSTANCE.toJson(removedInstrument.instrument))
                    }
                }
            }
            return removedInstruments
        }
        return emptyList()
    }

    fun _removeInstrument(instrument: LiveInstrument?, ex: Throwable?) {
        removeInstrument(instrument!!.location.source, instrument.location.line, instrument.id)
        val map: MutableMap<String, Any?> = HashMap()
        when (instrument) {
            is LiveBreakpoint -> map["breakpoint"] = ModelSerializer.INSTANCE.toJson(instrument)
            is LiveLog -> map["log"] = ModelSerializer.INSTANCE.toJson(instrument)
            is LiveMeter -> map["meter"] = ModelSerializer.INSTANCE.toJson(instrument)
            else -> throw IllegalArgumentException(instrument.javaClass.simpleName)
        }
        map["occurredAt"] = System.currentTimeMillis()
        if (ex != null) {
            map["cause"] = ThrowableTransformer.INSTANCE.convert2String(ex, 4000)
        }

        when (instrument) {
            is LiveBreakpoint -> instrumentEventConsumer!!.accept(
                PlatformAddress.LIVE_BREAKPOINT_REMOVED.address,
                ModelSerializer.INSTANCE.toJson(map)
            )
            is LiveLog -> instrumentEventConsumer!!.accept(
                PlatformAddress.LIVE_LOG_REMOVED.address,
                ModelSerializer.INSTANCE.toJson(map)
            )
            is LiveMeter -> instrumentEventConsumer!!.accept(
                PlatformAddress.LIVE_METER_REMOVED.address,
                ModelSerializer.INSTANCE.toJson(map)
            )
            else -> instrumentEventConsumer!!.accept(
                PlatformAddress.LIVE_SPAN_REMOVED.address,
                ModelSerializer.INSTANCE.toJson(map)
            )
        }
    }

    fun getInstruments(source: String, line: Int): List<ActiveLiveInstrument> {
        val instruments = instruments.values.stream()
            .filter { it.instrument.location.source == source && it.instrument.location.line == line }
            .collect(Collectors.toSet())
        instruments.addAll(
            applyingInstruments.values.stream()
                .filter { it.instrument.location.source == source && it.instrument.location.line == line }
                .collect(Collectors.toSet()))
        return ArrayList(instruments)
    }

    @JvmStatic
    fun isInstrumentEnabled(instrumentId: String): Boolean {
        val applied = instruments.containsKey(instrumentId)
        return if (applied) {
            true
        } else {
            applyingInstruments.containsKey(instrumentId)
        }
    }

    @JvmStatic
    fun isHit(instrumentId: String): Boolean {
        val instrument = instruments[instrumentId] ?: return false
        if (instrument.throttle?.isRateLimited() == true) {
            ContextReceiver.clear(instrumentId)
            return false
        }
        if (instrument.expression == null) {
            if (instrument.isFinished) {
                _removeInstrument(instrument.instrument, null)
            }
            return true
        }
        return try {
            if (evaluateCondition(instrument)) {
                if (instrument.isFinished) {
                    _removeInstrument(instrument.instrument, null)
                }
                true
            } else {
                ContextReceiver.clear(instrumentId)
                false
            }
        } catch (e: Throwable) {
            ContextReceiver.clear(instrumentId)
            _removeInstrument(instrument.instrument, e)
            false
        }
    }

    private fun evaluateCondition(liveInstrument: ActiveLiveInstrument): Boolean {
        val rootObject = ContextReceiver.get(liveInstrument.instrument.id)
        val context = StandardEvaluationContext(rootObject)
        return liveInstrument.expression!!.getValue(context, Boolean::class.java)
    }
}