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
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.variable.LiveVariableControl
import spp.protocol.instrument.variable.LiveVariableControlBase

class MaxObjectDepthControlTest : ProbeIntegrationTest() {

    private fun doTest() {
        val deepObject1 = DeepObject1()
        val deepObject11 = DeepObject1()
        val deeperObject1 = DeeperObject1()
    }

    @Test
    fun `max object depth variable control`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val instrumentId = testNameAsUniqueInstrumentId
        getLiveInstrumentSubscription(instrumentId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = event as LiveBreakpointHit
                    val vars = item.stackTrace.first().variables
                    assertEquals(4, vars.size)

                    //deeperObject1 is full depth
                    var deeperObject1 = (vars.first { it.name == "deeperObject1" }.value as JsonArray)
                        .first() as JsonObject
                    deeperObject1 = deeperObject1.getJsonArray("value").first() as JsonObject
                    deeperObject1 = deeperObject1.getJsonArray("value").first() as JsonObject
                    deeperObject1 = deeperObject1.getJsonArray("value").first() as JsonObject
                    deeperObject1 = deeperObject1.getJsonArray("value").first() as JsonObject
                    deeperObject1 = deeperObject1.getJsonArray("value").first() as JsonObject
                    deeperObject1 = deeperObject1.getJsonArray("value").first() as JsonObject
                    assertEquals(0, deeperObject1.getJsonArray("value").size())

                    testContext.completeNow()
                }
            }
        }

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    variableControl = LiveVariableControl(
                        maxObjectDepth = 8
                    ),
                    location = LiveSourceLocation(
                        source = MaxObjectDepthControlTest::class.java.name,
                        line = 42,
                        service = "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = instrumentId
                )
            ).await()
        )

        //trigger breakpoint
        doTest()

        errorOnTimeout(testContext)
    }

    @Test
    fun `max depth variable control by name`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val instrumentId = testNameAsUniqueInstrumentId
        getLiveInstrumentSubscription(instrumentId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = event as LiveBreakpointHit
                    val vars = item.stackTrace.first().variables
                    assertEquals(4, vars.size)

                    //deepObject1 is not full depth
                    var deepObject1 = (vars.first { it.name == "deepObject1" }.value as JsonArray)
                        .first() as JsonObject
                    deepObject1 = deepObject1.getJsonArray("value").first() as JsonObject
                    deepObject1 = deepObject1.getJsonArray("value").first() as JsonObject
                    deepObject1 = deepObject1.getJsonArray("value").first() as JsonObject
                    deepObject1 = deepObject1.getJsonArray("value").first() as JsonObject
                    deepObject1.getJsonObject("value").let {
                        assertEquals("MAX_DEPTH_EXCEEDED", it.getString("@skip"))
                        //todo: add skip[max], skip[current]
                    }

                    //deepObject11 is full depth
                    var deepObject11 = (vars.first { it.name == "deepObject11" }.value as JsonArray)
                        .first() as JsonObject
                    deepObject11 = deepObject11.getJsonArray("value").first() as JsonObject
                    deepObject11 = deepObject11.getJsonArray("value").first() as JsonObject
                    deepObject11 = deepObject11.getJsonArray("value").first() as JsonObject
                    deepObject11 = deepObject11.getJsonArray("value").first() as JsonObject
                    assertEquals(0, deepObject11.getJsonArray("value").size())

                    testContext.completeNow()
                }
            }
        }

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    variableControl = LiveVariableControl(
                        variableNameConfig = mapOf(
                            "deepObject11" to LiveVariableControlBase(
                                maxObjectDepth = 8
                            )
                        )
                    ),
                    location = LiveSourceLocation(
                        source = MaxObjectDepthControlTest::class.java.name,
                        line = 42,
                        service = "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = instrumentId
                )
            ).await()
        )

        //trigger breakpoint
        doTest()

        errorOnTimeout(testContext)
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

    class DeeperObject1 {
        val deeperObject2 = DeeperObject2()

        class DeeperObject2 {
            val deeperObject3 = DeeperObject3()

            class DeeperObject3 {
                val deeperObject4 = DeeperObject4()

                class DeeperObject4 {
                    val deeperObject5 = DeeperObject5()

                    class DeeperObject5 {
                        val deeperObject6 = DeeperObject6()

                        class DeeperObject6 {
                            val deeperObject7 = DeeperObject7()

                            class DeeperObject7 {
                                val deeperObject8 = DeeperObject8()

                                class DeeperObject8 {
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
