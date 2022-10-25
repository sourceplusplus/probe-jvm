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
import spp.protocol.instrument.variable.LiveVariableControl
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress

class MaxCollectionLengthControlTest : ProbeIntegrationTest() {

    private fun doTest() {
        val maxByteArr200 = ByteArray(200)
        val maxByteArr201 = ByteArray(201)
        val maxByteArr300 = ByteArray(300)
        val maxByteArr301 = ByteArray(301)
        val maxIntArr400 = IntArray(400)
    }

    @Test
    fun testVariableControl() = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = LiveBreakpointHit(JsonObject(event.data))
                    val vars = item.stackTrace.first().variables
                    assertEquals(6, vars.size)

                    //maxByteArr200
                    val maxByteArr200 = vars.first { it.name == "maxByteArr200" }
                    assertNotNull(maxByteArr200)
                    (maxByteArr200.value as JsonArray).let {
                        assertEquals(200, it.size())
                        for (i in 0 until 200) {
                            assertEquals(0, it.getJsonObject(i).getInteger("value"))
                        }
                    }

                    //maxByteArr201
                    val maxByteArr201 = vars.first { it.name == "maxByteArr201" }
                    assertNotNull(maxByteArr201)
                    (maxByteArr201.value as JsonArray).let {
                        assertEquals(201, it.size())
                        for (i in 0 until 200) {
                            assertEquals(0, it.getJsonObject(i).getInteger("value"))
                        }
                        val lastValue = it.getJsonObject(200).getJsonObject("value")
                        assertEquals("MAX_LENGTH_EXCEEDED", lastValue.getString("@skip"))
                        assertEquals(201, lastValue.getInteger("@skip[size]"))
                        assertEquals(200, lastValue.getInteger("@skip[max]"))
                    }

                    //maxByteArr300
                    val maxByteArr300 = vars.first { it.name == "maxByteArr300" }
                    assertNotNull(maxByteArr300)
                    (maxByteArr300.value as JsonArray).let {
                        assertEquals(300, it.size())
                        for (i in 0 until 300) {
                            assertEquals(0, it.getJsonObject(i).getInteger("value"))
                        }
                    }

                    //maxByteArr301
                    val maxByteArr301 = vars.first { it.name == "maxByteArr301" }
                    assertNotNull(maxByteArr301)
                    (maxByteArr301.value as JsonArray).let {
                        assertEquals(201, it.size())
                        for (i in 0 until 200) {
                            assertEquals(0, it.getJsonObject(i).getInteger("value"))
                        }
                        val lastValue = it.getJsonObject(200).getJsonObject("value")
                        assertEquals("MAX_LENGTH_EXCEEDED", lastValue.getString("@skip"))
                        assertEquals(301, lastValue.getInteger("@skip[size]"))
                        assertEquals(200, lastValue.getInteger("@skip[max]"))
                    }

                    //maxIntArr400
                    val maxIntArr400 = vars.first { it.name == "maxIntArr400" }
                    assertNotNull(maxIntArr400)
                    (maxIntArr400.value as JsonArray).let {
                        assertEquals(400, it.size())
                        for (i in 0 until 400) {
                            assertEquals(0, it.getJsonObject(i).getInteger("value"))
                        }
                    }

                    consumer.unregister()
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    variableControl = LiveVariableControl(
                        maxCollectionLength = 200,
                        variableNameConfig = mapOf(
                            "maxByteArr300" to LiveVariableControl(
                                maxCollectionLength = 300
                            )
                        ),
                        variableTypeConfig = mapOf(
                            "int[]" to LiveVariableControl(
                                maxCollectionLength = 400
                            )
                        )
                    ),
                    location = LiveSourceLocation(MaxCollectionLengthControlTest::class.qualifiedName!!, 44),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doTest()

        errorOnTimeout(testContext)
    }
}
