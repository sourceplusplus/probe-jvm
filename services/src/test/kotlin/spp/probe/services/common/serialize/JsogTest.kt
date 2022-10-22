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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import spp.probe.services.common.ModelSerializer
import java.lang.instrument.Instrumentation

class JsogTest {

    class RootSelfRef {
        var self: RootSelfRef? = null
    }

    @Test
    fun testRootSelfRef() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)

        val rootSelfRef = RootSelfRef()
        rootSelfRef.self = rootSelfRef
        val json = ModelSerializer.INSTANCE.toExtendedJson(rootSelfRef)
        val jsonObject = JsonObject(json)

        assertEquals(3, jsonObject.size())
        assertTrue(jsonObject.containsKey("@class"))
        assertTrue(jsonObject.containsKey("@id"))
        assertTrue(jsonObject.containsKey("self"))

        val self = jsonObject.getJsonObject("self")
        assertEquals(2, self.size())
        assertTrue(self.containsKey("@class"))
        assertTrue(self.containsKey("@ref"))
        assertEquals(self.getString("@ref"), jsonObject.getString("@id"))
    }

    class InnerSelfRef {
        class InnerSelfRef2 {
            var selfRef: InnerSelfRef? = null
        }

        var self2: InnerSelfRef2? = null
    }

    @Test
    fun testInnerSelfRef() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)

        val innerSelfRef = InnerSelfRef()
        innerSelfRef.self2 = InnerSelfRef.InnerSelfRef2().apply { this.selfRef = innerSelfRef }
        val json = ModelSerializer.INSTANCE.toExtendedJson(innerSelfRef)
        val jsonObject = JsonObject(json)

        assertEquals(3, jsonObject.size())
        assertTrue(jsonObject.containsKey("@class"))
        assertTrue(jsonObject.containsKey("@id"))
        assertTrue(jsonObject.containsKey("self2"))

        val self2 = jsonObject.getJsonObject("self2")
        assertEquals(3, self2.size())
        assertTrue(self2.containsKey("@class"))
        assertTrue(self2.containsKey("@id"))
        assertTrue(self2.containsKey("selfRef"))

        val selfRef = self2.getJsonObject("selfRef")
        assertEquals(2, selfRef.size())
        assertTrue(selfRef.containsKey("@class"))
        assertTrue(selfRef.containsKey("@ref"))
        assertEquals(selfRef.getString("@ref"), jsonObject.getString("@id"))
    }
}
