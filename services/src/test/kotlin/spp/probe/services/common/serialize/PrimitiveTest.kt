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
package spp.probe.services.common.serialize

import io.vertx.core.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import spp.probe.services.common.ModelSerializer
import java.lang.instrument.Instrumentation

class PrimitiveTest {

    @Test
    fun `primitive types`() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)

        //int
        ModelSerializer.INSTANCE.toExtendedJson(1).let {
            assertEquals("1", it)
        }
        //long
        ModelSerializer.INSTANCE.toExtendedJson(1L).let {
            assertEquals("1", it)
        }
        //float
        ModelSerializer.INSTANCE.toExtendedJson(1.0f).let {
            assertEquals("1.0", it)
        }
        //double
        ModelSerializer.INSTANCE.toExtendedJson(1.0).let {
            assertEquals("1.0", it)
        }
        //boolean
        ModelSerializer.INSTANCE.toExtendedJson(true).let {
            assertEquals("true", it)
        }
        //char
        ModelSerializer.INSTANCE.toExtendedJson('a').let {
            assertEquals("\"a\"", it)
        }
        //string
        ModelSerializer.INSTANCE.toExtendedJson("a").let {
            assertEquals("\"a\"", it)
        }
    }

    @Test
    fun `primitive arrays`() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)

        //int
        ModelSerializer.INSTANCE.toExtendedJson(intArrayOf(1, 2, 3)).let {
            assertEquals("[1,2,3]", it)
        }
        //long
        ModelSerializer.INSTANCE.toExtendedJson(longArrayOf(1L, 2L, 3L)).let {
            assertEquals("[1,2,3]", it)
        }
        //float
        ModelSerializer.INSTANCE.toExtendedJson(floatArrayOf(1.0f, 2.0f, 3.0f)).let {
            assertEquals("[1.0,2.0,3.0]", it)
        }
        //double
        ModelSerializer.INSTANCE.toExtendedJson(doubleArrayOf(1.0, 2.0, 3.0)).let {
            assertEquals("[1.0,2.0,3.0]", it)
        }
        //boolean
        ModelSerializer.INSTANCE.toExtendedJson(booleanArrayOf(true, false, true)).let {
            assertEquals("[true,false,true]", it)
        }
        //char
        ModelSerializer.INSTANCE.toExtendedJson(charArrayOf('a', 'b', 'c')).let {
            assertEquals("[\"a\",\"b\",\"c\"]", it)
        }
        //string
        ModelSerializer.INSTANCE.toExtendedJson(arrayOf("a", "b", "c")).let {
            assertEquals("[\"a\",\"b\",\"c\"]", it)
        }
    }

    @Test
    fun `primitive lists`() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)

        //int
        ModelSerializer.INSTANCE.toExtendedJson(listOf(1, 2, 3)).let {
            assertEquals("[1,2,3]", it)
        }
        //long
        ModelSerializer.INSTANCE.toExtendedJson(listOf(1L, 2L, 3L)).let {
            assertEquals("[1,2,3]", it)
        }
        //float
        ModelSerializer.INSTANCE.toExtendedJson(listOf(1.0f, 2.0f, 3.0f)).let {
            assertEquals("[1.0,2.0,3.0]", it)
        }
        //double
        ModelSerializer.INSTANCE.toExtendedJson(listOf(1.0, 2.0, 3.0)).let {
            assertEquals("[1.0,2.0,3.0]", it)
        }
        //boolean
        ModelSerializer.INSTANCE.toExtendedJson(listOf(true, false, true)).let {
            assertEquals("[true,false,true]", it)
        }
        //char
        ModelSerializer.INSTANCE.toExtendedJson(listOf('a', 'b', 'c')).let {
            assertEquals("[\"a\",\"b\",\"c\"]", it)
        }
        //string
        ModelSerializer.INSTANCE.toExtendedJson(listOf("a", "b", "c")).let {
            assertEquals("[\"a\",\"b\",\"c\"]", it)
        }
    }

    @Test
    fun `primitive maps`() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)

        //int
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(mapOf("a" to 1, "b" to 2, "c" to 3))).let {
            assertEquals("java.util.LinkedHashMap", it.getString("@class"))
            assertEquals(1, it.getInteger("a"))
            assertEquals(2, it.getInteger("b"))
            assertEquals(3, it.getInteger("c"))
        }
        //long
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(mapOf("a" to 1L, "b" to 2L, "c" to 3L))).let {
            assertEquals("java.util.LinkedHashMap", it.getString("@class"))
            assertEquals(1, it.getLong("a"))
            assertEquals(2, it.getLong("b"))
            assertEquals(3, it.getLong("c"))
        }
        //float
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(mapOf("a" to 1.0f, "b" to 2.0f, "c" to 3.0f))).let {
            assertEquals("java.util.LinkedHashMap", it.getString("@class"))
            assertEquals(1.0, it.getDouble("a"), 0.0)
            assertEquals(2.0, it.getDouble("b"), 0.0)
            assertEquals(3.0, it.getDouble("c"), 0.0)
        }
        //double
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(mapOf("a" to 1.0, "b" to 2.0, "c" to 3.0))).let {
            assertEquals("java.util.LinkedHashMap", it.getString("@class"))
            assertEquals(1.0, it.getDouble("a"), 0.0)
            assertEquals(2.0, it.getDouble("b"), 0.0)
            assertEquals(3.0, it.getDouble("c"), 0.0)
        }
        //boolean
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(mapOf("a" to true, "b" to false, "c" to true))).let {
            assertEquals("java.util.LinkedHashMap", it.getString("@class"))
            assertEquals(true, it.getBoolean("a"))
            assertEquals(false, it.getBoolean("b"))
            assertEquals(true, it.getBoolean("c"))
        }
        //char
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(mapOf("a" to 'a', "b" to 'b', "c" to 'c'))).let {
            assertEquals("java.util.LinkedHashMap", it.getString("@class"))
            assertEquals("a", it.getString("a"))
            assertEquals("b", it.getString("b"))
            assertEquals("c", it.getString("c"))
        }
        //string
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(mapOf("a" to "a", "b" to "b", "c" to "c"))).let {
            assertEquals("java.util.LinkedHashMap", it.getString("@class"))
            assertEquals("a", it.getString("a"))
            assertEquals("b", it.getString("b"))
            assertEquals("c", it.getString("c"))
        }
    }
}
