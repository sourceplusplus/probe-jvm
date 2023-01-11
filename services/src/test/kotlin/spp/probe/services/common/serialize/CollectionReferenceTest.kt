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

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.probe.services.common.ModelSerializer

class CollectionReferenceTest : AbstractSerializeTest {

    @Test
    fun `collection references`() {
        val refObject = RefObject()

        //array
        val refArr = arrayOf(refObject, refObject)
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(refArr)).let { json ->
            assertEquals(2, json.size())
            val realObject = json.getJsonObject(0)
            assertEquals(2, realObject.size())
            assertEquals("id", realObject.getString("id"))
            assertNotNull(realObject.getString("@id"))

            val refObject = json.getJsonObject(1)
            assertEquals(1, refObject.size())
            assertEquals(realObject.getString("@id"), refObject.getString("@ref"))
        }

        //list
        val refList = listOf(refObject, refObject)
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(refList)).let { json ->
            assertEquals(2, json.size())
            val realObject = json.getJsonObject(0)
            assertEquals(2, realObject.size())
            assertEquals("id", realObject.getString("id"))
            assertNotNull(realObject.getString("@id"))

            val refObject = json.getJsonObject(1)
            assertEquals(1, refObject.size())
            assertEquals(realObject.getString("@id"), refObject.getString("@ref"))
        }

        //map
        val refMap = mapOf("ref" to refObject, "ref2" to refObject)
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(refMap)).let { json ->
            assertEquals(4, json.size())
            assertEquals("java.util.LinkedHashMap", json.getString("@class"))
            assertNotNull(json.getString("@id"))

            val realObject = json.getJsonObject("ref")
            assertEquals(2, realObject.size())
            assertEquals("id", realObject.getString("id"))
            assertNotNull(realObject.getString("@id"))

            val refObject = json.getJsonObject("ref2")
            assertEquals(1, refObject.size())
            assertEquals(realObject.getString("@id"), refObject.getString("@ref"))
        }
    }

    data class RefObject(val id: String = "id")
}
