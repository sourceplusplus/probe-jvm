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

class MaxObjectSizeTest : AbstractSerializeTest {

    @Test
    fun `max size exceeded`() {
        ProbeConfiguration.localProperties = JsonObject().put(
            "spp", JsonObject().put(
                "live_variable_control", JsonObject()
                    .put("max_object_depth", -1)
                    .put("max_object_size", 0)
                    .put("max_collection_size", -1)
            )
        )
        val instrumentation = Mockito.mock(Instrumentation::class.java).apply {
            Mockito.`when`(this.getObjectSize(Mockito.any())).thenReturn(1024)
        }
        CappedTypeAdapterFactory.setInstrumentation(instrumentation)

        val twoMbArr = "fakeMaxSizeObject"
        val json = JsonObject(ModelSerializer.INSTANCE.toExtendedJson(twoMbArr))

        assertEquals("MAX_SIZE_EXCEEDED", json.getString("@skip"))
        assertEquals("java.lang.String", json.getString("@class"))
        assertEquals(1024, json.getInteger("@size"))
        assertNotNull(json.getString("@id"))
    }
}
