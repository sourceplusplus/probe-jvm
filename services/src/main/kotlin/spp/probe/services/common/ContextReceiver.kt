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
package spp.probe.services.common

import org.apache.skywalking.apm.agent.core.boot.ServiceManager
import org.apache.skywalking.apm.agent.core.conf.Config
import org.apache.skywalking.apm.agent.core.context.ContextManager
import org.apache.skywalking.apm.agent.core.context.tag.StringTag
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.apache.skywalking.apm.agent.core.meter.Counter
import org.apache.skywalking.apm.agent.core.meter.CounterMode
import org.apache.skywalking.apm.agent.core.meter.Histogram
import org.apache.skywalking.apm.agent.core.meter.MeterFactory
import org.apache.skywalking.apm.agent.core.remote.LogReportServiceClient
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair
import org.apache.skywalking.apm.network.logging.v3.*
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.LiveSpan
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValueType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Suppress("unused")
object ContextReceiver {

    private val localVariables: MutableMap<String?, MutableMap<String, Pair<String, Any?>>> = ConcurrentHashMap()
    private val fields: MutableMap<String?, MutableMap<String, Pair<String, Any?>>> = ConcurrentHashMap()
    private val staticFields: MutableMap<String?, MutableMap<String, Pair<String, Any?>>> = ConcurrentHashMap()
    private val logReport = ServiceManager.INSTANCE.findService(LogReportServiceClient::class.java)
    private val executor = Executors.newFixedThreadPool(5)

    operator fun get(instrumentId: String): ContextMap {
        val contextMap = ContextMap()
        contextMap.fields = fields[instrumentId]
        contextMap.localVariables = localVariables[instrumentId]
        contextMap.staticFields = staticFields[instrumentId]
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

    private fun addInstrumentVariable(
        instrumentId: String, key: String, value: Any?, type: String,
        variableMap: MutableMap<String?, MutableMap<String, Pair<String, Any?>>>
    ) {
        variableMap.computeIfAbsent(instrumentId) { HashMap() }[key] = Pair(type, value)
    }

    @JvmStatic
    fun putBreakpoint(breakpointId: String, source: String?, line: Int, throwable: Throwable) = executor.submit {
        val activeSpan = ContextManager.createLocalSpan(throwable.stackTrace[0].toString())
        localVariables.remove(breakpointId)?.forEach { (key: String, value: Pair<String, Any?>) ->
            activeSpan.tag(
                StringTag("spp.local-variable:$breakpointId:$key"), encodeObject(key, value)
            )
        }
        fields.remove(breakpointId)?.forEach { (key: String, value: Pair<String, Any?>) ->
            activeSpan.tag(
                StringTag("spp.field:$breakpointId:$key"), encodeObject(key, value)
            )
        }
        staticFields.remove(breakpointId)?.forEach { (key: String, value: Pair<String, Any?>) ->
            activeSpan.tag(
                StringTag("spp.static-field:$breakpointId:$key"), encodeObject(key, value)
            )
        }
        activeSpan.tag(
            StringTag("spp.stack-trace:$breakpointId"),
            ThrowableTransformer.INSTANCE.convert2String(throwable, 4000)
        )
        activeSpan.tag(
            StringTag("spp.breakpoint:$breakpointId"),
            ModelSerializer.INSTANCE.toJson(LiveSourceLocation(source!!, line, null, null, null, null))
        )
        ContextManager.stopSpan(activeSpan)
    }

    @JvmStatic
    fun putLog(logId: String?, logFormat: String?, vararg logArguments: String) = executor.submit {
        val localVars: Map<String, Any?>? = localVariables.remove(logId)
        val localFields: Map<String, Any?>? = fields.remove(logId)
        val localStaticFields: Map<String, Any?>? = staticFields.remove(logId)
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
                var argValue = localVars?.get(logArguments[i])
                if (argValue == null) {
                    argValue = localFields?.get(logArguments[i])
                    if (argValue == null) {
                        argValue = localStaticFields?.get(logArguments[i])
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
        val liveMeter = ProbeMemory["spp.live-meter:$meterId"] as LiveMeter? ?: return@submit
        val baseMeter = ProbeMemory.computeIfAbsent("spp.base-meter:$meterId") {
            when (liveMeter.meterType) {
                MeterType.COUNT -> return@computeIfAbsent MeterFactory.counter(
                    "count_" + meterId.replace("-", "_")
                ).mode(CounterMode.valueOf(liveMeter.meta.getOrDefault("metric.mode", "INCREMENT") as String))
                    .build()

                MeterType.GAUGE -> return@computeIfAbsent MeterFactory.gauge(
                    "gauge_" + meterId.replace("-", "_")
                ) { liveMeter.metricValue.value.toDouble() }
                    .build()

                MeterType.HISTOGRAM -> return@computeIfAbsent MeterFactory.histogram(
                    "histogram_" + meterId.replace("-", "_")
                )
                    .steps(listOf(0.0)) //todo: dynamic
                    .build()

                else -> throw UnsupportedOperationException("Unsupported meter type: ${liveMeter.meterType}")
            }
        }
        when (liveMeter.meterType) {
            MeterType.COUNT -> if (liveMeter.metricValue.valueType == MetricValueType.NUMBER) {
                (baseMeter as Counter).increment(liveMeter.metricValue.value.toLong().toDouble())
            } else {
                throw UnsupportedOperationException("todo") //todo: this
            }

            MeterType.GAUGE -> {}
            MeterType.HISTOGRAM -> if (liveMeter.metricValue.valueType == MetricValueType.NUMBER) {
                (baseMeter as Histogram).addValue(liveMeter.metricValue.value.toDouble())
            } else {
                throw UnsupportedOperationException("todo") //todo: this
            }

            else -> throw UnsupportedOperationException("Unsupported meter type: ${liveMeter.meterType}")
        }
    }

    @JvmStatic
    fun openLocalSpan(spanId: String) {
        val liveSpan = ProbeMemory["spp.live-span:$spanId"] as LiveSpan? ?: return
        val activeSpan = ContextManager.createLocalSpan(liveSpan.operationName)
        activeSpan.tag(StringTag("spanId"), spanId)
        ProbeMemory.put("spp.active-span:$spanId", activeSpan)
    }

    @JvmStatic
    fun closeLocalSpan(spanId: String, throwable: Throwable? = null) {
        val activeSpan = ProbeMemory.remove("spp.active-span:$spanId") as AbstractSpan? ?: return
        throwable?.let { activeSpan.log(it) }
        ContextManager.stopSpan(activeSpan)
    }

    private fun encodeObject(varName: String, varData: Pair<String, Any?>): String? {
        val value = varData.second ?: return String.format(
            "{\"@class\":\"%s\",\"@null\":true,\"$varName\":%s}", varData.first, null
        )

        return try {
            String.format(
                "{\"@class\":\"%s\",\"@id\":\"%s\",\"$varName\":%s}",
                value.javaClass.name, Integer.toHexString(System.identityHashCode(value)),
                ModelSerializer.INSTANCE.toExtendedJson(value)
            )
        } catch (ex: Exception) {
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
