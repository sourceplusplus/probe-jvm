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
package spp.probe.services.common

import org.apache.skywalking.apm.agent.core.boot.ServiceManager
import org.apache.skywalking.apm.agent.core.conf.Config
import org.apache.skywalking.apm.agent.core.context.ContextManager
import org.apache.skywalking.apm.agent.core.context.tag.StringTag
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import org.apache.skywalking.apm.agent.core.meter.Counter
import org.apache.skywalking.apm.agent.core.meter.CounterMode
import org.apache.skywalking.apm.agent.core.meter.Histogram
import org.apache.skywalking.apm.agent.core.meter.MeterFactory
import org.apache.skywalking.apm.agent.core.remote.LogReportServiceClient
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair
import org.apache.skywalking.apm.network.logging.v3.*
import org.springframework.expression.spel.support.StandardEvaluationContext
import spp.probe.services.instrument.LiveInstrumentService
import spp.protocol.instrument.*
import spp.protocol.instrument.meter.MeterTagValueType
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValueType
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.function.Supplier

@Suppress("unused")
object ContextReceiver {

    private val log = LogManager.getLogger(ContextReceiver::class.java)
    private val localVariables: MutableMap<String?, MutableMap<String, Pair<String, Any?>>> = ConcurrentHashMap()
    private val fields: MutableMap<String?, MutableMap<String, Pair<String, Any?>>> = ConcurrentHashMap()
    private val staticFields: MutableMap<String?, MutableMap<String, Pair<String, Any?>>> = ConcurrentHashMap()
    private val logReport = ServiceManager.INSTANCE.findService(LogReportServiceClient::class.java)
    private val executor = Executors.newFixedThreadPool(5)

    operator fun get(instrumentId: String): ContextMap {
        val contextMap = ContextMap()
        contextMap.fields = fields[instrumentId]?.map { it.key to it.value.second }?.toMap()
        contextMap.localVariables = localVariables[instrumentId]?.map { it.key to it.value.second }?.toMap()
        contextMap.staticFields = staticFields[instrumentId]?.map { it.key to it.value.second }?.toMap()
        return contextMap
    }

    fun clear(instrumentId: String) {
        fields.remove(instrumentId)
        localVariables.remove(instrumentId)
        staticFields.remove(instrumentId)
    }

    @JvmStatic
    fun putLocalVariable(instrumentId: String, key: String, value: Any?, type: String) {
        addInstrumentVariable(instrumentId, key, value, type, localVariables)
    }

    @JvmStatic
    fun putField(instrumentId: String, key: String, value: Any?, type: String) {
        addInstrumentVariable(instrumentId, key, value, type, fields)
    }

    @JvmStatic
    fun putStaticField(instrumentId: String, key: String, value: Any?, type: String) {
        addInstrumentVariable(instrumentId, key, value, type, staticFields)
    }

    @JvmStatic
    fun putReturn(instrumentId: String, value: Any?, type: String) {
        addInstrumentVariable(instrumentId, "@return", value, type, localVariables)
    }

    private fun addInstrumentVariable(
        instrumentId: String, key: String, value: Any?, type: String,
        variableMap: MutableMap<String?, MutableMap<String, Pair<String, Any?>>>
    ) {
        variableMap.computeIfAbsent(instrumentId) { HashMap() }[key] = Pair(type, value)
    }

    @JvmStatic
    fun putBreakpoint(breakpointId: String, source: String?, line: Int, throwable: Throwable) = executor.submit {
        if (log.isDebugEnable) log.debug("Breakpoint hit: $breakpointId")
        val liveBreakpoint = ProbeMemory.remove("spp.live-instrument:$breakpointId") as LiveBreakpoint? ?: return@submit
        if (log.isDebugEnable) log.debug("Live breakpoint: $liveBreakpoint")

        val activeSpan = ContextManager.createLocalSpan(throwable.stackTrace[0].toString())
        localVariables.remove(breakpointId)?.forEach { (key: String, value: Pair<String, Any?>) ->
            activeSpan.tag(
                StringTag("spp.local-variable:$breakpointId:$key"), encodeObject(liveBreakpoint, key, value)
            )
        }
        fields.remove(breakpointId)?.forEach { (key: String, value: Pair<String, Any?>) ->
            activeSpan.tag(
                StringTag("spp.field:$breakpointId:$key"), encodeObject(liveBreakpoint, key, value)
            )
        }
        staticFields.remove(breakpointId)?.forEach { (key: String, value: Pair<String, Any?>) ->
            activeSpan.tag(
                StringTag("spp.static-field:$breakpointId:$key"), encodeObject(liveBreakpoint, key, value)
            )
        }
        activeSpan.tag(
            StringTag("spp.stack-trace:$breakpointId"),
            ThrowableTransformer.INSTANCE.convert2String(throwable, 4000)
        )
        activeSpan.tag(
            StringTag("spp.breakpoint:$breakpointId"),
            ModelSerializer.INSTANCE.toJson(LiveSourceLocation(source!!, line, null, null, null, null, null))
        )
        ContextManager.stopSpan(activeSpan)
    }

    @JvmStatic
    fun putLog(logId: String?, logFormat: String?, vararg logArguments: String?) = executor.submit {
        if (log.isDebugEnable) log.debug("Log hit: $logId")
        val liveLog = ProbeMemory.remove("spp.live-instrument:$logId") as LiveLog? ?: return@submit
        if (log.isDebugEnable) log.debug("Live log: $liveLog")

        val localVars = localVariables.remove(logId)
        val localFields = fields.remove(logId)
        val localStaticFields = staticFields.remove(logId)
        val logTags = LogTags.newBuilder()
            .addData(
                KeyStringValuePair.newBuilder()
                    .setKey("log_id").setValue(logId).build()
            )
            .addData(
                KeyStringValuePair.newBuilder()
                    .setKey("level").setValue("Live").build()
            )
            .addData(
                KeyStringValuePair.newBuilder()
                    .setKey("thread").setValue(Thread.currentThread().name).build()
            )
        if (logArguments.isNotEmpty()) {
            for (i in logArguments.indices) {
                //todo: is it smarter to pass localVariables[arg]?
                var argValue = localVars?.get(logArguments[i])?.second
                if (argValue == null) {
                    argValue = localFields?.get(logArguments[i])?.second
                    if (argValue == null) {
                        argValue = localStaticFields?.get(logArguments[i])?.second
                    }
                }
                val value = Optional.ofNullable(argValue).orElse("null")
                logTags.addData(
                    KeyStringValuePair.newBuilder()
                        .setKey("argument.$i").setValue(value.toString()).build()
                )
            }
        }
        val builder = LogData.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setService(Config.Agent.SERVICE_NAME)
            .setServiceInstance(Config.Agent.INSTANCE_NAME)
            .apply { ContextManager.getPrimaryEndpointName()?.let { endpoint = it } }
            .setTags(logTags.build())
            .setBody(
                LogDataBody.newBuilder().setType(LogDataBody.ContentCase.TEXT.name)
                    .setText(TextLog.newBuilder().setText(logFormat).build()).build()
            )
        val logData = if (-1 == ContextManager.getSpanId()) builder else builder.setTraceContext(
            TraceContext.newBuilder()
                .setTraceId(ContextManager.getGlobalTraceId())
                .setSpanId(ContextManager.getSpanId())
                .setTraceSegmentId(ContextManager.getSegmentId())
                .build()
        )
        logReport.produce(logData)
    }

    @JvmStatic
    fun putMeter(meterId: String) = executor.submit {
        if (log.isDebugEnable) log.debug("Meter hit: $meterId")
        val liveMeter = ProbeMemory.remove("spp.live-instrument:$meterId") as LiveMeter? ?: return@submit
        if (log.isDebugEnable) log.debug("Live meter: $liveMeter")

        //calculate meter tags
        val tagMap = HashMap<String, String>()
        liveMeter.meterTags.forEach {
            if (it.valueType == MeterTagValueType.VALUE) {
                tagMap[it.key] = it.value
            } else if (it.valueType == MeterTagValueType.VALUE_EXPRESSION) {
                val rootObject = ContextReceiver[liveMeter.id!!]
                val context = StandardEvaluationContext(rootObject)
                val expression = LiveInstrumentService.parser.parseExpression(it.value)
                val value = try {
                    expression.getValue(context, Any::class.java)
                } catch (e: Exception) {
                    log.error("Failed to evaluate expression: ${it.value}", e)
                    null
                }
                tagMap[it.key] = value?.toString() ?: "null"
            }
        }
        val tagStr = tagMap.entries.joinToString(",") { "${it.key}=${it.value}" }

        val baseMeter = ProbeMemory.computeIfAbsent("spp.base-meter:$meterId{$tagStr}") {
            log.info("Initial trigger of live meter: $meterId")
            when (liveMeter.meterType) {
                MeterType.COUNT -> {
                    return@computeIfAbsent MeterFactory.counter(liveMeter.toMetricIdWithoutPrefix())
                        .mode(CounterMode.valueOf(liveMeter.meta.getOrDefault("metric.mode", "INCREMENT") as String))
                        .apply {
                            tagMap.forEach {
                                tag(it.key, it.value)
                            }
                        }.build()
                }

                MeterType.GAUGE -> {
                    if (liveMeter.metricValue?.valueType == MetricValueType.NUMBER_SUPPLIER) {
                        val decoded = Base64.getDecoder().decode(liveMeter.metricValue!!.value)

                        @Suppress("UNCHECKED_CAST")
                        val supplier = ObjectInputStream(
                            ByteArrayInputStream(decoded)
                        ).readObject() as Supplier<Double>
                        return@computeIfAbsent MeterFactory.gauge(liveMeter.toMetricIdWithoutPrefix(), supplier)
                            .apply {
                                tagMap.forEach {
                                    tag(it.key, it.value)
                                }
                            }.build()
                    } else if (liveMeter.metricValue?.valueType == MetricValueType.NUMBER_EXPRESSION) {
                        return@computeIfAbsent MeterFactory.gauge(liveMeter.toMetricIdWithoutPrefix()) {
                            val rootObject = ContextReceiver[liveMeter.id!!]
                            val context = StandardEvaluationContext(rootObject)
                            val expression = LiveInstrumentService.parser.parseExpression(liveMeter.metricValue!!.value)
                            val value = expression.getValue(context, Any::class.java)
                            if (value is Number) {
                                value?.toDouble() ?: 0.0
                            } else {
                                //todo: remove instrument
                                log.error("Unsupported expression value type: ${value?.javaClass?.name}")
                                Double.MIN_VALUE
                            }
                        }.apply {
                            tagMap.forEach {
                                tag(it.key, it.value)
                            }
                        }.build()
                    } else if (liveMeter.metricValue?.valueType == MetricValueType.VALUE_EXPRESSION) {
                        return@computeIfAbsent MeterFactory.gauge(liveMeter.toMetricIdWithoutPrefix()) {
                            val rootObject = ContextReceiver[liveMeter.id!!]
                            val context = StandardEvaluationContext(rootObject)
                            val expression = LiveInstrumentService.parser.parseExpression(liveMeter.metricValue!!.value)
                            val value = expression.getValue(context, Any::class.java)

                            //send gauge log
                            val logTags = LogTags.newBuilder()
                                .addData(
                                    KeyStringValuePair.newBuilder()
                                        .setKey("meter_id").setValue(liveMeter.id).build()
                                ).addData(
                                    KeyStringValuePair.newBuilder()
                                        .setKey("metric_id").setValue(liveMeter.toMetricId()).build()
                                )
                            val builder = LogData.newBuilder()
                                .setTimestamp(System.currentTimeMillis())
                                .setService(Config.Agent.SERVICE_NAME)
                                .setServiceInstance(Config.Agent.INSTANCE_NAME)
                                .setTags(logTags.build())
                                .setBody(
                                    LogDataBody.newBuilder().setType(LogDataBody.ContentCase.TEXT.name)
                                        .setText(TextLog.newBuilder().setText(value.toString()).build()).build()
                                )
                            val logData =
                                if (-1 == ContextManager.getSpanId()) builder else builder.setTraceContext(
                                    TraceContext.newBuilder()
                                        .setTraceId(ContextManager.getGlobalTraceId())
                                        .setSpanId(ContextManager.getSpanId())
                                        .setTraceSegmentId(ContextManager.getSegmentId())
                                        .build()
                                )
                            logReport.produce(logData)

                            Double.MIN_VALUE
                        }.apply {
                            tagMap.forEach {
                                tag(it.key, it.value)
                            }
                        }.build()
                    } else {
                        return@computeIfAbsent MeterFactory.gauge(liveMeter.toMetricIdWithoutPrefix()) {
                            liveMeter.metricValue!!.value.toDouble()
                        }.apply {
                            tagMap.forEach {
                                tag(it.key, it.value)
                            }
                        }.build()
                    }
                }

                MeterType.HISTOGRAM -> return@computeIfAbsent MeterFactory.histogram(
                    "histogram_" + meterId.replace("-", "_")
                )
                    .steps(listOf(0.0)) //todo: dynamic
                    .apply {
                        tagMap.forEach {
                            tag(it.key, it.value)
                        }
                    }.build()

                else -> throw UnsupportedOperationException("Unsupported meter type: ${liveMeter.meterType}")
            }
        }
        when (liveMeter.meterType) {
            MeterType.COUNT -> if (liveMeter.metricValue?.valueType == MetricValueType.NUMBER) {
                (baseMeter as Counter).increment(liveMeter.metricValue!!.value.toLong().toDouble())
            } else {
                throw UnsupportedOperationException("todo") //todo: this
            }

            MeterType.GAUGE -> {}
            MeterType.HISTOGRAM -> if (liveMeter.metricValue?.valueType == MetricValueType.NUMBER) {
                (baseMeter as Histogram).addValue(liveMeter.metricValue!!.value.toDouble())
            } else {
                throw UnsupportedOperationException("todo") //todo: this
            }

            else -> throw UnsupportedOperationException("Unsupported meter type: ${liveMeter.meterType}")
        }
    }

    @JvmStatic
    fun openLocalSpan(spanId: String) {
        if (log.isDebugEnable) log.debug("Open local span: $spanId")
        val liveSpan = ProbeMemory.remove("spp.live-instrument:$spanId") as LiveSpan? ?: return
        if (log.isDebugEnable) log.debug("Live span: $liveSpan")

        val activeSpan = ContextManager.createLocalSpan(liveSpan.operationName)
        activeSpan.tag(StringTag("spanId"), spanId)
        ProbeMemory.put("spp.active-span:$spanId", activeSpan)
    }

    @JvmStatic
    fun closeLocalSpan(spanId: String, throwable: Throwable? = null) {
        if (log.isDebugEnable) log.debug("Close local span: $spanId")
        val activeSpan = ProbeMemory.remove("spp.active-span:$spanId") as AbstractSpan? ?: return
        if (log.isDebugEnable) log.debug("Active span: $activeSpan")

        throwable?.let { activeSpan.log(it) }
        ContextManager.stopSpan(activeSpan)
    }

    private fun encodeObject(breakpoint: LiveBreakpoint, varName: String, varData: Pair<String, Any?>): String? {
        val value = varData.second ?: return String.format(
            "{\"@class\":\"%s\",\"@null\":true,\"$varName\":%s}", varData.first, null
        )

        return try {
            String.format(
                "{\"@class\":\"%s\",\"@id\":\"%s\",\"$varName\":%s}",
                value.javaClass.name, Integer.toHexString(System.identityHashCode(value)),
                ModelSerializer.INSTANCE.toExtendedJson(value, varName, breakpoint)
            )
        } catch (ex: Throwable) {
            log.error("Failed to encode object: $varName", ex)

            try {
                val map: MutableMap<String, Any?> = HashMap()
                map[varName] = value.javaClass.name + "@" + Integer.toHexString(System.identityHashCode(value))
                map["@class"] = "java.lang.Class"
                map["@id"] = Integer.toHexString(System.identityHashCode(value))
                map["@skip"] = "EXCEPTION_OCCURRED"
                map["@cause"] = ex.message
                return ModelSerializer.INSTANCE.toJson(map)
            } catch (ignore: Exception) {
            }
            value.toString() //can't reach here
        }
    }
}
