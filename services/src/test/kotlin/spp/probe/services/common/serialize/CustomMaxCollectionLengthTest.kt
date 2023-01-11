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
import org.junit.jupiter.api.Test
import spp.probe.ProbeConfiguration
import spp.probe.services.common.ModelSerializer

class CustomMaxCollectionLengthTest : AbstractSerializeTest {

    @Test
    fun `custom max length by type`() {
        ProbeConfiguration.variableControlByType.put(
            "byte[]",
            JsonObject().put("max_collection_length", 200)
        )

        val maxByteArr = ByteArray(200)
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxByteArr)).let { json ->
            assertEquals(200, json.size())
            for (i in 0..199) {
                assertEquals(0, json.getInteger(i))
            }
        }

        val maxIntArr = IntArray(101)
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxIntArr)).let { json ->
            assertEquals(101, json.size())
            for (i in 0..99) {
                assertEquals(0, json.getInteger(i))
            }

            val maxSizeOb = json.getJsonObject(100)
            assertEquals("MAX_LENGTH_EXCEEDED", maxSizeOb.getString("@skip"))
            assertEquals(maxIntArr.size, maxSizeOb.getInteger("@skip[size]"))
            assertEquals(100, maxSizeOb.getInteger("@skip[max]"))
        }

        ProbeConfiguration.variableControlByType.put(
            "int[]",
            JsonObject().put("max_collection_length", 200)
        )

        val maxIntArr2 = IntArray(200)
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxIntArr2)).let { json ->
            assertEquals(200, json.size())
            for (i in 0..199) {
                assertEquals(0, json.getInteger(i))
            }
        }
    }

    @Test
    fun `custom max length by name`() {
        ProbeConfiguration.variableControlByName.put(
            "maxByteArr",
            JsonObject().put("max_collection_length", 200)
        )

        val maxByteArr = ByteArray(200)

        //try without name
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxByteArr)).let { json ->
            assertEquals(101, json.size())
            for (i in 0..99) {
                assertEquals(0, json.getInteger(i))
            }

            val maxSizeOb = json.getJsonObject(100)
            assertEquals("MAX_LENGTH_EXCEEDED", maxSizeOb.getString("@skip"))
            assertEquals(maxByteArr.size, maxSizeOb.getInteger("@skip[size]"))
            assertEquals(100, maxSizeOb.getInteger("@skip[max]"))
        }

        //try with wrong name
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxByteArr, "wrongName")).let { json ->
            assertEquals(101, json.size())
            for (i in 0..99) {
                assertEquals(0, json.getInteger(i))
            }

            val maxSizeOb = json.getJsonObject(100)
            assertEquals("MAX_LENGTH_EXCEEDED", maxSizeOb.getString("@skip"))
            assertEquals(maxByteArr.size, maxSizeOb.getInteger("@skip[size]"))
            assertEquals(100, maxSizeOb.getInteger("@skip[max]"))
        }

        //try with correct name
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxByteArr, "maxByteArr")).let { json ->
            assertEquals(200, json.size())
            for (i in 0..199) {
                assertEquals(0, json.getInteger(i))
            }
        }
    }
}
