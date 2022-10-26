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
package integration.breakpoint

import integration.ProbeIntegrationTest
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress

class CollectionsTest : ProbeIntegrationTest() {

    private fun doTest() {
        val emptyList = emptyList<String>()
        val byteArr = byteArrayOf(1, 2, 3)
        val intArr = intArrayOf(1, 2, 3)
        val stringSet = setOf("a", "b", "c")
        val doubleMap = mapOf(1.0 to 1.1, 2.0 to 2.1, 3.0 to 3.1)
        val arrOfArrays = arrayOf(intArrayOf(1, 2, 3), intArrayOf(4, 5, 6))
        val mapOfMaps = mapOf(
            "a" to mapOf("a" to 1, "b" to 2, "c" to 3),
            "b" to mapOf("a" to 4, "b" to 5, "c" to 6)
        )
        val listOfLists = listOf(
            listOf(1, 2, 3),
            listOf(4, 5, 6)
        )
    }

    @Test
    fun testCollections() = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val bpHit = LiveBreakpointHit(JsonObject(event.data))
                    val topFrame = bpHit.stackTrace.elements.first()
                    assertEquals(9, topFrame.variables.size)

                    //emptyList
                    assertEquals(JsonArray(), topFrame.variables.find { it.name == "emptyList" }!!.value)
                    assertEquals(
                        "kotlin.collections.EmptyList",
                        topFrame.variables.find { it.name == "emptyList" }!!.liveClazz
                    )

                    //byteArr
                    assertEquals(
                        listOf(1, 2, 3),
                        topFrame.variables.find { it.name == "byteArr" }!!.value.let {
                            (it as JsonArray).map { JsonObject.mapFrom(it) }.map { it.getInteger("value") }
                        }
                    )
                    assertEquals(
                        "byte[]",
                        topFrame.variables.find { it.name == "byteArr" }!!.liveClazz
                    )

                    //intArr
                    assertEquals(
                        listOf(1, 2, 3),
                        topFrame.variables.find { it.name == "intArr" }!!.value.let {
                            (it as JsonArray).map { JsonObject.mapFrom(it) }.map { it.getInteger("value") }
                        }
                    )
                    assertEquals(
                        "int[]",
                        topFrame.variables.find { it.name == "intArr" }!!.liveClazz
                    )

                    //stringSet
                    assertEquals(
                        listOf("a", "b", "c"),
                        topFrame.variables.find { it.name == "stringSet" }!!.value.let {
                            (it as JsonArray).map { JsonObject.mapFrom(it) }.map { it.getString("value") }
                        }
                    )
                    assertEquals(
                        "java.util.LinkedHashSet",
                        topFrame.variables.find { it.name == "stringSet" }!!.liveClazz
                    )

                    //doubleMap
                    assertEquals(
                        listOf(1.0 to 1.1, 2.0 to 2.1, 3.0 to 3.1),
                        topFrame.variables.find { it.name == "doubleMap" }!!.value.let {
                            (it as JsonArray).map { JsonObject.mapFrom(it) }
                                .map { it.getString("name").toDouble() to it.getDouble("value") }
                        }
                    )
                    assertEquals(
                        "java.util.LinkedHashMap",
                        topFrame.variables.find { it.name == "doubleMap" }!!.liveClazz
                    )

                    //arrOfArrays
                    assertEquals(
                        listOf(
                            listOf(1, 2, 3),
                            listOf(4, 5, 6)
                        ),
                        topFrame.variables.find { it.name == "arrOfArrays" }!!.value.let {
                            (it as JsonArray).map { JsonObject.mapFrom(it) }.map {
                                (it.getJsonArray("value") as JsonArray).map { it as Int }
                            }
                        }
                    )
                    assertEquals(
                        "int[][]",
                        topFrame.variables.find { it.name == "arrOfArrays" }!!.liveClazz
                    )

                    //mapOfMaps
                    assertEquals(
                        listOf(
                            listOf("a" to 1, "b" to 2, "c" to 3),
                            listOf("a" to 4, "b" to 5, "c" to 6)
                        ),
                        topFrame.variables.find { it.name == "mapOfMaps" }!!.value.let {
                            (it as JsonArray).map { JsonObject.mapFrom(it) }.map {
                                (it.getJsonArray("value") as JsonArray).map { JsonObject.mapFrom(it) }
                                    .map { it.getString("name") to it.getInteger("value") }
                            }
                        }
                    )
                    assertEquals(
                        "java.util.LinkedHashMap",
                        topFrame.variables.find { it.name == "mapOfMaps" }!!.liveClazz
                    )

                    //listOfLists
                    assertEquals(
                        listOf(
                            listOf(1, 2, 3),
                            listOf(4, 5, 6)
                        ),
                        topFrame.variables.find { it.name == "listOfLists" }!!.value.let {
                            (it as JsonArray).map { JsonObject.mapFrom(it) }.map {
                                (it.getJsonArray("value") as JsonArray).map { it as Int }
                            }
                        }
                    )
                    assertEquals(
                        "java.util.Arrays\$ArrayList",
                        topFrame.variables.find { it.name == "listOfLists" }!!.liveClazz
                    )

                    consumer.unregister()
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(CollectionsTest::class.qualifiedName!!, 52),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
    }
}
