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
package spp.probe.monitors

import java.lang.ref.PhantomReference
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue

/**
 * Used to monitor the lifespan of an object.
 */
object ObjectLifespanMonitor {

    private val queue = ReferenceQueue<Any>()
    private val totalLifespan = mutableMapOf<Class<*>, Long>()
    private val monitoredObjects = mutableMapOf<Int, Pair<Reference<*>, Int>>()

    init {
        val timer = MonitorTimer()
        timer.name = "spp-object-lifespan-monitor-timer"
        timer.priority = Thread.MAX_PRIORITY
        timer.isDaemon = true
        timer.start()
    }

    /**
     * Add an object to be monitored.
     *
     * @param obj the object to be monitored
     * @return the total lifespan of all objects of the given type
     */
    fun monitor(obj: Any): Double {
        val objectClass = obj.javaClass

        var ref: Reference<*>? = null
        while (queue.poll()?.let { ref = it } != null) {
            //object destroyed
            val lifespan = MonitorTimer.time - monitoredObjects.remove(System.identityHashCode(ref))!!.second
            totalLifespan[objectClass] = totalLifespan.getOrDefault(objectClass, 0) + lifespan
        }

        //object created
        ref = PhantomReference(obj, queue)
        monitoredObjects[System.identityHashCode(ref)] = Pair(ref!!, MonitorTimer.time)

        return (totalLifespan.remove(objectClass) ?: 0L).toDouble() * 100.0
    }

    private class MonitorTimer : Thread() {
        companion object {
            private const val SLEEP_INTERVAL = 100

            @Volatile
            var time = 0
        }

        override fun run() {
            while (true) {
                try {
                    sleep(SLEEP_INTERVAL.toLong())
                    time++
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    return
                }
            }
        }
    }
}
