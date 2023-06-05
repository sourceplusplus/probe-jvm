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
package spp.probe.services.common.serialize

import io.vertx.core.json.JsonObject
import org.apache.skywalking.apm.agent.core.meter.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import spp.probe.ProbeConfiguration
import spp.probe.services.common.ModelSerializer
import java.lang.instrument.Instrumentation
import java.util.concurrent.ConcurrentHashMap

class ObjectMapTest : AbstractSerializeTest {

    @Test
    fun `sw meter map`() {
        ProbeConfiguration.instrumentation = Mockito.mock(Instrumentation::class.java).apply {
            Mockito.`when`(this.getObjectSize(Mockito.any())).thenReturn(1024)
        }

        val map = ConcurrentHashMap<MeterId, BaseMeter>()
        val meterId1 = MeterId("test1", MeterType.COUNTER, emptyList())
        map[meterId1] = Counter(meterId1, CounterMode.RATE).apply { increment(2.0) }
        val meterId2 = MeterId("test2", MeterType.COUNTER, emptyList())
        map[meterId2] = Counter(meterId2, CounterMode.INCREMENT).apply { increment(3.0) }

        val json = JsonObject(ModelSerializer.INSTANCE.toExtendedJson(map))
        assertEquals(Integer.toHexString(System.identityHashCode(map)), json.getString("@id"))
        assertEquals("java.util.concurrent.ConcurrentHashMap", json.getString("@class"))
        assertEquals(4, json.size())

        val meter1 = JsonObject.mapFrom(json.map.values.filterIsInstance<Map<*, *>>().find {
            JsonObject.mapFrom(it).getString("@id") == Integer.toHexString(System.identityHashCode(map[meterId1]))
        })
        assertEquals(2.0, meter1.getDouble("count"))
        assertEquals("RATE", meter1.getString("mode"))

        val meter2 = JsonObject.mapFrom(json.map.values.filterIsInstance<Map<*, *>>().find {
            JsonObject.mapFrom(it).getString("@id") == Integer.toHexString(System.identityHashCode(map[meterId2]))
        })
        assertEquals(3.0, meter2.getDouble("count"))
        assertEquals("INCREMENT", meter2.getString("mode"))
    }
}
