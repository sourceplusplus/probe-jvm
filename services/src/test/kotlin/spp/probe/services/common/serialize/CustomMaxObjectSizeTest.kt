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
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito
import spp.probe.ProbeConfiguration
import spp.probe.services.common.ModelSerializer
import java.lang.instrument.Instrumentation

class CustomMaxObjectSizeTest : AbstractSerializeTest {

    @Test
    fun `custom max size by type`() {
        ProbeConfiguration.variableControlByType.put(
            "java.lang.String",
            JsonObject().put("max_object_size", 1025)
        )
        ProbeConfiguration.variableControlByType.put(
            "java.lang.Character",
            JsonObject().put("max_object_size", 0)
        )
        ProbeConfiguration.instrumentation = Mockito.mock(Instrumentation::class.java).apply {
            Mockito.`when`(this.getObjectSize(Mockito.any())).thenReturn(1024)
        }

        val fakeMaxSizeString = "fakeMaxSizeObject"
        ModelSerializer.INSTANCE.toExtendedJson(fakeMaxSizeString).let {
            assertEquals("\"fakeMaxSizeObject\"", it)
        }

        val fakeMaxSizeChar = 'a'
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(fakeMaxSizeChar)).let {
            assertEquals("MAX_SIZE_EXCEEDED", it.getString("@skip"))
            assertEquals("java.lang.Character", it.getString("@class"))
            assertEquals(1024, it.getInteger("@skip[size]"))
            assertEquals(0, it.getInteger("@skip[max]"))
            assertNotNull(it.getString("@id"))
        }
    }

    @Test
    fun `custom max size by name`() {
        ProbeConfiguration.variableControl.put("max_object_size", 0)
        ProbeConfiguration.variableControlByName.put(
            "fakeMaxSizeString",
            JsonObject().put("max_object_size", 1025)
        )
        ProbeConfiguration.instrumentation = Mockito.mock(Instrumentation::class.java).apply {
            Mockito.`when`(this.getObjectSize(Mockito.any())).thenReturn(1024)
        }

        val fakeMaxSizeString = "fakeMaxSizeObject"

        //try without name
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(fakeMaxSizeString)).let {
            assertEquals("MAX_SIZE_EXCEEDED", it.getString("@skip"))
            assertEquals("java.lang.String", it.getString("@class"))
            assertEquals(1024, it.getInteger("@skip[size]"))
            assertEquals(0, it.getInteger("@skip[max]"))
            assertNotNull(it.getString("@id"))
        }

        //try with wrong name
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(fakeMaxSizeString, "fakeMaxSizeString2")).let {
            assertEquals("MAX_SIZE_EXCEEDED", it.getString("@skip"))
            assertEquals("java.lang.String", it.getString("@class"))
            assertEquals(1024, it.getInteger("@skip[size]"))
            assertEquals(0, it.getInteger("@skip[max]"))
            assertNotNull(it.getString("@id"))
        }

        //try with correct name
        ModelSerializer.INSTANCE.toExtendedJson(fakeMaxSizeString, "fakeMaxSizeString").let {
            assertEquals("\"fakeMaxSizeObject\"", it)
        }
    }

    @Test
    fun `custom max size by inner name`() {
        ProbeConfiguration.variableControlByType.put(
            "spp.probe.services.common.serialize.CustomMaxObjectSizeTest\$OuterObject",
            JsonObject().put("max_object_size", 1025)
        )
        ProbeConfiguration.variableControl.put("max_object_size", 0)
        ProbeConfiguration.variableControlByName.put(
            "fakeMaxSizeString",
            JsonObject().put("max_object_size", 1025)
        )
        ProbeConfiguration.instrumentation = Mockito.mock(Instrumentation::class.java).apply {
            Mockito.`when`(this.getObjectSize(Mockito.any())).thenReturn(1024)
        }

        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(OuterObject())).let {
            assertEquals(4, it.size())
            assertNotNull(it.getString("@class"))
            assertNotNull(it.getString("@id"))
            assertEquals("fakeMaxSizeString", it.getString("fakeMaxSizeString"))
            it.getJsonObject("fakeMaxSizeString2").let {
                assertEquals("MAX_SIZE_EXCEEDED", it.getString("@skip"))
                assertEquals("java.lang.String", it.getString("@class"))
                assertEquals(1024, it.getInteger("@skip[size]"))
                assertEquals(0, it.getInteger("@skip[max]"))
                assertNotNull(it.getString("@id"))
            }
        }
    }

    inner class OuterObject {
        var fakeMaxSizeString = "fakeMaxSizeString"
        val fakeMaxSizeString2 = "fakeMaxSizeString2"
    }
}
