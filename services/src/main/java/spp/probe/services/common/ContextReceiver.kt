package spp.probe.services.common

import org.apache.skywalking.apm.agent.core.boot.ServiceManager
import org.apache.skywalking.apm.agent.core.conf.Config
import org.apache.skywalking.apm.agent.core.context.ContextManager
import org.apache.skywalking.apm.agent.core.context.tag.StringTag
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer
import org.apache.skywalking.apm.agent.core.meter.Counter
import org.apache.skywalking.apm.agent.core.meter.Histogram
import org.apache.skywalking.apm.agent.core.meter.MeterFactory
import org.apache.skywalking.apm.agent.core.remote.LogReportServiceClient
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair
import org.apache.skywalking.apm.network.logging.v3.*
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.meter.LiveMeter
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValueType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object ContextReceiver {
    private val ignoredVariables = Pattern.compile(
        "(_\\\$EnhancedClassField_ws)|((delegate|cachedValue)\\$[a-zA-Z0-9\$]+)"
    )
    private val localVariables: MutableMap<String?, MutableMap<String, Any>> = ConcurrentHashMap()
    private val fields: MutableMap<String?, MutableMap<String, Any>> = ConcurrentHashMap()
    private val staticFields: MutableMap<String?, MutableMap<String, Any>> = ConcurrentHashMap()
    private val logReport = ServiceManager.INSTANCE.findService(
        LogReportServiceClient::class.java
    )

    operator fun get(instrumentId: String?): ContextMap {
        val contextMap = ContextMap()
        contextMap.fields = fields[instrumentId]
        contextMap.localVariables = localVariables[instrumentId]
        contextMap.staticFields = staticFields[instrumentId]
        return contextMap
    }

    fun clear(instrumentId: String?) {
        fields.remove(instrumentId)
        localVariables.remove(instrumentId)
        staticFields.remove(instrumentId)
    }

    fun putLocalVariable(instrumentId: String, key: String, value: Any?) {
        addInstrumentVariable(instrumentId, key, value, localVariables)
    }

    fun putField(instrumentId: String, key: String, value: Any?) {
        addInstrumentVariable(instrumentId, key, value, fields)
    }

    fun putStaticField(instrumentId: String, key: String, value: Any?) {
        addInstrumentVariable(instrumentId, key, value, staticFields)
    }

    private fun addInstrumentVariable(
        instrumentId: String, key: String, value: Any?,
        variableMap: MutableMap<String?, MutableMap<String, Any>>
    ) {
        if (value == null) {
            return
        } else if (ignoredVariables.matcher(key).matches()) {
            return
        }
        variableMap.computeIfAbsent(instrumentId) { it: String? -> HashMap() }[key] = value
    }

    fun putBreakpoint(breakpointId: String, source: String?, line: Int, throwable: Throwable) {
        val activeSpan = ContextManager.createLocalSpan(throwable.stackTrace[0].toString())
        val localVars: Map<String, Any>? = localVariables.remove(breakpointId)
        localVars?.forEach { (key: String, value: Any) ->
            activeSpan.tag(
                StringTag("spp.local-variable:$breakpointId:$key"), encodeObject(key, value)
            )
        }
        val localFields: Map<String, Any>? = fields.remove(breakpointId)
        localFields?.forEach { (key: String, value: Any) ->
            activeSpan.tag(
                StringTag("spp.field:$breakpointId:$key"), encodeObject(key, value)
            )
        }
        val localStaticFields: Map<String, Any>? = staticFields.remove(breakpointId)
        localStaticFields?.forEach { (key: String, value: Any) ->
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
            ModelSerializer.INSTANCE.toJson(LiveSourceLocation(source!!, line))
        )
        ContextManager.stopSpan(activeSpan)
    }

    fun putLog(logId: String?, logFormat: String?, vararg logArguments: String) {
        val localVars: Map<String, Any>? = localVariables.remove(logId)
        val localFields: Map<String, Any>? = fields.remove(logId)
        val localStaticFields: Map<String, Any>? = staticFields.remove(logId)
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
        if (logArguments.size > 0) {
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
        val logData = if (-1 == ContextManager.getSpanId()) builder.build() else builder.setTraceContext(
            TraceContext.newBuilder()
                .setTraceId(ContextManager.getGlobalTraceId())
                .setSpanId(ContextManager.getSpanId())
                .setTraceSegmentId(ContextManager.getSegmentId())
                .build()
        ).build()
        logReport.produce(logData)
    }

    fun putMeter(meterId: String) {
        val (_, meterType, metricValue) = ProbeMemory.get("spp.live-meter:$meterId") as LiveMeter ?: return
        val meter = ProbeMemory.computeIfAbsent("spp.base-meter:$meterId") { it: String? ->
            when (meterType) {
                MeterType.COUNTER -> return@computeIfAbsent MeterFactory.counter("counter_" + meterId.replace("-", "_"))
                    .build()
                MeterType.GAUGE -> return@computeIfAbsent MeterFactory.gauge(
                    "gauge_" + meterId.replace("-", "_")
                ) { metricValue.value.toDouble() }
                    .build()
                MeterType.HISTOGRAM -> return@computeIfAbsent MeterFactory.histogram(
                    "histogram_" + meterId.replace(
                        "-",
                        "_"
                    )
                )
                    .steps(listOf(0.0)) //todo: dynamic
                    .build()
                else -> throw UnsupportedOperationException("Unsupported meter type: $meterType")
            }
        }
        when (meterType) {
            MeterType.COUNTER -> if (metricValue.valueType == MetricValueType.NUMBER) {
                (meter as Counter).increment(
                    metricValue.value.toLong().toDouble()
                )
            } else {
                throw UnsupportedOperationException("todo") //todo: this
            }
            MeterType.GAUGE -> {}
            MeterType.HISTOGRAM -> if (metricValue.valueType == MetricValueType.NUMBER) {
                (meter as Histogram).addValue(metricValue.value.toDouble())
            } else {
                throw UnsupportedOperationException("todo") //todo: this
            }
            else -> throw UnsupportedOperationException("Unsupported meter type: $meterType")
        }
    }

    private fun encodeObject(varName: String, value: Any): String? {
        return try {
            String.format(
                "{\"@class\":\"%s\",\"@identity\":\"%s\",\"$varName\":%s}",
                value.javaClass.name, Integer.toHexString(System.identityHashCode(value)),
                ModelSerializer.INSTANCE.toExtendedJson(value)
            )
        } catch (ex: Exception) {
            try {
                val map: MutableMap<String, Any?> = HashMap()
                map[varName] = value.javaClass.name + "@" + Integer.toHexString(System.identityHashCode(value))
                map["@class"] = "java.lang.Class"
                map["@identity"] = Integer.toHexString(System.identityHashCode(value))
                map["@ex"] = ex.message
                return ModelSerializer.INSTANCE.toJson(map)
            } catch (ignore: Exception) {
            }
            value.toString() //can't reach here
        }
    }
}