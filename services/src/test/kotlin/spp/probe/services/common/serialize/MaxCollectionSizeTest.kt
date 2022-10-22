/*
 * Source++, the open-source live coding platform.
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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import spp.probe.services.common.ModelSerializer
import java.lang.instrument.Instrumentation

class MaxCollectionSizeTest {

    @Test
    fun `max size exceeded`() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)

        //array
        val maxArr = ByteArray(101)
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxArr)).let { json ->
            assertEquals(101, json.size())
            for (i in 0..99) {
                assertEquals(0, json.getInteger(i))
            }

            val maxSizeOb = json.getJsonObject(100)
            assertEquals("MAX_COLLECTION_SIZE_EXCEEDED", maxSizeOb.getString("@skip"))
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
            assertEquals("MAX_COLLECTION_SIZE_EXCEEDED", maxSizeOb.getString("@skip"))
            assertEquals(maxList.size, maxSizeOb.getInteger("@skip[size]"))
            assertEquals(100, maxSizeOb.getInteger("@skip[max]"))
        }

        //map
        val maxMap = mutableMapOf<String, Int>().apply {
            for (i in 0..100) put(i.toString(), i)
        }
        JsonArray(ModelSerializer.INSTANCE.toExtendedJson(maxMap)).let { json ->
            assertEquals(101, json.size())
            for (i in 0..99) {
                assertEquals(i, json.getJsonObject(i).getInteger(i.toString()))
            }

            val maxSizeOb = json.getJsonObject(100)
            assertEquals("MAX_COLLECTION_SIZE_EXCEEDED", maxSizeOb.getString("@skip"))
            assertEquals(maxList.size, maxSizeOb.getInteger("@skip[size]"))
            assertEquals(100, maxSizeOb.getInteger("@skip[max]"))
        }
    }
}
