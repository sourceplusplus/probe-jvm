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

import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import spp.probe.ProbeConfiguration
import spp.probe.services.LiveInstrumentRemote
import spp.probe.services.common.ModelSerializer
import spp.probe.services.common.model.ActiveLiveInstrument
import spp.probe.services.common.transform.LiveTransformer
import spp.probe.services.error.LiveInstrumentException
import spp.protocol.platform.ProcessorAddress
import java.lang.instrument.Instrumentation
import java.lang.instrument.UnmodifiableClassException
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class QueuedLiveInstrumentApplier : LiveInstrumentApplier {

    companion object {
        val threadLocal: ThreadLocal<Queue<Class<*>>> = ThreadLocal.withInitial { ConcurrentLinkedQueue() }
    }

    private val log = LogManager.getLogger(QueuedLiveInstrumentApplier::class.java)
    private val transformer = LiveTransformer()
    private val queue = ConcurrentLinkedQueue<WorkLoad>()
    private val timer = Timer("QueuedLiveInstrumentApplier", true)

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
        var clazz: Class<*>? = inst.allLoadedClasses.find { it.name == className }
        if (clazz != null && log.isInfoEnable) log.info("Found {} in all loaded classes", clazz)
        if (clazz == null) {
            try {
                clazz = Class.forName(className, false, javaClass.classLoader)
                log.info("Found {} in Class.forName", clazz)
            } catch (ignore: Exception) {
            }
        }
        if (clazz == null) {
            if (instrument.instrument.applyImmediately) {
                log.warn(
                    "Unable to find {}. Live instrument {} cannot be applied immediately",
                    className, instrument.instrument.id
                )
                throw LiveInstrumentException(
                    LiveInstrumentException.ErrorType.CLASS_NOT_FOUND,
                    className
                ).toEventBusException()
            } else {
                log.info(
                    "Unable to find {}. Live instrument {} will be applied when the class is loaded",
                    className, instrument.instrument.id
                )
                return
            }
        }

        if (instrument.instrument.applyImmediately) {
            doTransform(WorkLoad(clazz, instrument, inst))
        } else {
            queue.add(WorkLoad(clazz, instrument, inst))
        }
    }

    init {
        ProbeConfiguration.instrumentation!!.addTransformer(transformer, true)
        timer.schedule(object : TimerTask() {
            override fun run() {
                while (true) {
                    val workLoad = queue.poll() ?: break
                    doTransform(workLoad)
                }
            }
        }, 500, 500)
    }

    private fun doTransform(workLoad: WorkLoad) {
        val clazz = workLoad.clazz
        val instrument = workLoad.instrument
        val instrumentation = workLoad.instrumentation

        try {
            do {
                instrumentation.retransformClasses(workLoad.clazz)
                workLoad.clazz = threadLocal.get().poll()
            } while (workLoad.clazz != null)
            threadLocal.remove()

            if (instrument.isApplied && instrument.sentAppliedEvent.compareAndSet(false, true)) {
                if (log.isInfoEnable) log.info("Successfully applied live instrument: {}", instrument.instrument.id)
                LiveInstrumentRemote.EVENT_CONSUMER.accept(
                    ProcessorAddress.LIVE_INSTRUMENT_APPLIED,
                    ModelSerializer.INSTANCE.toJson(instrument.instrument)
                )
            }
            if (instrument.isRemoval) {
                if (log.isInfoEnable) log.info("Successfully removed live instrument: {}", instrument.instrument.id)
            }
        } catch (ex: Throwable) {
            log.warn(ex, "Failed to apply live instrument: {}", instrument.instrument.id)

            //remove and re-transform
            LiveInstrumentService.removeInstrument(instrument.instrument, ex)
            try {
                instrumentation.retransformClasses(clazz)
            } catch (e: UnmodifiableClassException) {
                log.warn(e, "Failed to re-transform class: {}", clazz)
                throw RuntimeException(e)
            }
        }
    }

    private data class WorkLoad(
        var clazz: Class<*>?,
        val instrument: ActiveLiveInstrument,
        val instrumentation: Instrumentation
    )
}
