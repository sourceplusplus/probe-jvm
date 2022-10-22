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

import io.vertx.core.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito
import spp.probe.services.common.ModelSerializer
import java.lang.instrument.Instrumentation

class MaxObjectDepthTest {

    @Test
    fun `max depth exceeded`() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)
        CappedTypeAdapterFactory.setUnsafeMode(false)

        doTest()
    }

    @Test
    fun `unsafe max depth exceeded`() {
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)
        CappedTypeAdapterFactory.setUnsafeMode(true)

        doTest()
    }

    private fun doTest() {
        val deepObject = DeepObject1()
        val json = JsonObject(ModelSerializer.INSTANCE.toExtendedJson(deepObject))

        assertNotNull(json.getString("@class"))
        assertNotNull(json.getString("@id"))
        var deepObjectJson = json.getJsonObject("deepObject2")
        for (i in 3..6) {
            assertNotNull(deepObjectJson)
//            assertNotNull(deepObjectJson.getString("@class"))
            assertNotNull(deepObjectJson.getString("@id"))
            deepObjectJson = deepObjectJson.getJsonObject("deepObject$i")
        }
        assertNotNull(deepObjectJson)
//        assertEquals("MAX_DEPTH_EXCEEDED", deepObjectJson.getString("@skip"))
//        assertEquals(0, deepObjectJson.getInteger("@size"))
//        assertNotNull(deepObjectJson.getString("@class"))
        assertNotNull(deepObjectJson.getString("@id"))
    }

    class DeepObject1 {
        val deepObject2 = DeepObject2()

        class DeepObject2 {
            val deepObject3 = DeepObject3()

            class DeepObject3 {
                val deepObject4 = DeepObject4()

                class DeepObject4 {
                    val deepObject5 = DeepObject5()

                    class DeepObject5 {
                        val deepObject6 = DeepObject6()

                        class DeepObject6 {
                        }
                    }
                }
            }
        }
    }
}
