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

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import spp.probe.services.common.ModelSerializer

class MaxCollectionLengthTest : AbstractSerializeTest {

    @Test
    fun `max length exceeded`() {
        //array
        val maxArr = ByteArray(101)
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxArr)).let { json ->
            assertEquals(101, json.size())
            for (i in 0..99) {
                assertEquals(0, json.getInteger(i))
            }

            val maxSizeOb = json.getJsonObject(100)
            assertEquals("MAX_LENGTH_EXCEEDED", maxSizeOb.getString("@skip"))
            assertEquals(maxArr.size, maxSizeOb.getInteger("@skip[size]"))
            assertEquals(100, maxSizeOb.getInteger("@skip[max]"))
        }

        //list
        val maxList = List(101) { 0 }
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxList)).let { json ->
            assertEquals(101, json.size())
            for (i in 0..99) {
                assertEquals(0, json.getInteger(i))
            }

            val maxSizeOb = json.getJsonObject(100)
            assertEquals("MAX_LENGTH_EXCEEDED", maxSizeOb.getString("@skip"))
            assertEquals(maxList.size, maxSizeOb.getInteger("@skip[size]"))
            assertEquals(100, maxSizeOb.getInteger("@skip[max]"))
        }

        //map
        val maxMap = mutableMapOf<String, Int>().apply {
            for (i in 0..100) put(i.toString(), i)
        }
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(maxMap)).let { json ->
            assertEquals(105, json.size())
            assertEquals("MAX_LENGTH_EXCEEDED", json.getString("@skip"))
            assertEquals(maxList.size, json.getInteger("@skip[size]"))
            assertEquals(100, json.getInteger("@skip[max]"))
            assertNotNull(json.getString("@id"))
            assertEquals("java.util.LinkedHashMap", json.getString("@class"))

            for (i in 0..99) {
                assertEquals(i, json.getString(i.toString()).toInt())
            }
        }
    }

    @Test
    fun `less than max length`() {
        //array
        val unboundedArr = ByteArray(10)
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(unboundedArr)).let { json ->
            assertEquals(10, json.size())
            for (i in 0..9) {
                assertEquals(0, json.getInteger(i))
            }
        }

        //list
        val unboundedList = List(10) { 0 }
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(unboundedList)).let { json ->
            assertEquals(10, json.size())
            for (i in 0..9) {
                assertEquals(0, json.getInteger(i))
            }
        }

        //map
        val unboundedMap = mutableMapOf<String, Int>().apply {
            for (i in 0..9) put(i.toString(), i)
        }
        JsonObject(ModelSerializer.INSTANCE.toExtendedJson(unboundedMap)).let { json ->
            assertEquals(12, json.size())
            assertNotNull(json.getString("@id"))
            assertEquals("java.util.LinkedHashMap", json.getString("@class"))

            for (i in 0..9) {
                assertEquals(i, json.getString(i.toString()).toInt())
            }
        }
    }
}
