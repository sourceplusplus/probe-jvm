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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import spp.probe.ProbeConfiguration
import spp.probe.services.common.ModelSerializer

@Isolated
class CustomMaxObjectDepthTest : AbstractSerializeTest {

    @Test
    fun `custom max depth by type`() {
        ProbeConfiguration.variableControlByType.put(
            "spp.probe.services.common.serialize.CustomMaxObjectDepthTest\$DeepObject1",
            JsonObject().put("max_object_depth", 6)
        )
        val deepObject = DeepObject1()
        val json = JsonObject(ModelSerializer.INSTANCE.toExtendedJson(deepObject))

        assertNotNull(json.getString("@class"))
        assertNotNull(json.getString("@id"))
        assertEquals(3, json.size())
        var deepObjectJson = json.getJsonObject("deepObject2")
        for (i in 3..6) {
            assertNotNull(deepObjectJson)
            assertNotNull(deepObjectJson.getString("@class"))
            assertNotNull(deepObjectJson.getString("@id"))
            assertEquals(3, deepObjectJson.size())
            deepObjectJson = deepObjectJson.getJsonObject("deepObject$i")
        }
        assertNotNull(deepObjectJson)
        assertNotNull(deepObjectJson.getString("@class"))
        assertNotNull(deepObjectJson.getString("@id"))
        assertEquals(2, deepObjectJson.size())
    }

    @Test
    fun `custom max depth by name`() {
        ProbeConfiguration.variableControlByName.put(
            "multiDeepObject2",
            JsonObject().put("max_object_depth", 6)
        )
        val deepObject = MultiDeepObject1()
        val json = JsonObject(ModelSerializer.INSTANCE.toExtendedJson(deepObject))

        //multiDeepObject2 is full depth
        assertNotNull(json.getString("@class"))
        assertNotNull(json.getString("@id"))
        assertEquals(4, json.size())
        var multiDeepObjectJson = json.getJsonObject("multiDeepObject2")
        for (i in 3..6) {
            assertNotNull(multiDeepObjectJson)
            assertNotNull(multiDeepObjectJson.getString("@class"))
            assertNotNull(multiDeepObjectJson.getString("@id"))
            assertEquals(3, multiDeepObjectJson.size())
            multiDeepObjectJson = multiDeepObjectJson.getJsonObject("multiDeepObject$i")
        }
        assertNotNull(multiDeepObjectJson)
        assertNotNull(multiDeepObjectJson.getString("@class"))
        assertNotNull(multiDeepObjectJson.getString("@id"))
        assertEquals(2, multiDeepObjectJson.size())

        //deepObject2 is not full depth
        var deepObjectJson = json.getJsonObject("deepObject2")
        for (i in 3..6) {
            assertNotNull(deepObjectJson)
            assertNotNull(deepObjectJson.getString("@class"))
            assertNotNull(deepObjectJson.getString("@id"))
            assertEquals(3, deepObjectJson.size())
            deepObjectJson = deepObjectJson.getJsonObject("deepObject$i")
        }
        assertNotNull(deepObjectJson)
        assertEquals("MAX_DEPTH_EXCEEDED", deepObjectJson.getString("@skip"))
        assertEquals(0, deepObjectJson.getInteger("@size"))
        assertNotNull(deepObjectJson.getString("@class"))
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

                        class DeepObject6
                    }
                }
            }
        }
    }

    class MultiDeepObject1 {
        val multiDeepObject2 = MultiDeepObject2()
        val deepObject2 = DeepObject1.DeepObject2()

        class MultiDeepObject2 {
            val multiDeepObject3 = MultiDeepObject3()

            class MultiDeepObject3 {
                val multiDeepObject4 = MultiDeepObject4()

                class MultiDeepObject4 {
                    val multiDeepObject5 = MultiDeepObject5()

                    class MultiDeepObject5 {
                        val multiDeepObject6 = MultiDeepObject6()

                        class MultiDeepObject6
                    }
                }
            }
        }
    }
}
