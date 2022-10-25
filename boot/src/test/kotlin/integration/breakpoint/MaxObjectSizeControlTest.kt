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

class MaxObjectSizeControlTest : ProbeIntegrationTest() {

    private fun doTest() {
        val fakeMaxSizeInt = 1
        val fakeMaxSizeChar = 'a'
        val fakeMaxSizeString = "fakeMaxSizeObject"
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
                    assertEquals(4, vars.size)

                    //fakeMaxSizeInt
                    val fakeMaxSizeInt = vars.first { it.name == "fakeMaxSizeInt" }
                    assertNotNull(fakeMaxSizeInt)
                    assertEquals(1, fakeMaxSizeInt.value)

                    //fakeMaxSizeChar
                    val fakeMaxSizeChar = vars.first { it.name == "fakeMaxSizeChar" }
                    assertNotNull(fakeMaxSizeChar)
                    (fakeMaxSizeChar.value as JsonObject).let {
                        assertEquals(5, it.size())
                        assertEquals("MAX_SIZE_EXCEEDED", it.getString("@skip"))
                        assertEquals("java.lang.Character", it.getString("@class"))
                        assertEquals(16, it.getInteger("@skip[size]"))
                        assertEquals(8, it.getInteger("@skip[max]"))
                        assertNotNull(it.getString("@id"))
                    }

                    //fakeMaxSizeString
                    val fakeMaxSizeString = vars.first { it.name == "fakeMaxSizeString" }
                    assertNotNull(fakeMaxSizeString)
                    (fakeMaxSizeString.value as JsonObject).let {
                        assertEquals(5, it.size())
                        assertEquals("MAX_SIZE_EXCEEDED", it.getString("@skip"))
                        assertEquals("java.lang.String", it.getString("@class"))
                        assertEquals(24, it.getInteger("@skip[size]"))
                        assertEquals(4, it.getInteger("@skip[max]"))
                        assertNotNull(it.getString("@id"))
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
                        maxObjectSize = 16,
                        variableNameConfig = mapOf(
                            "fakeMaxSizeChar" to LiveVariableControl(
                                maxObjectSize = 8
                            )
                        ),
                        variableTypeConfig = mapOf(
                            "java.lang.String" to LiveVariableControl(
                                maxObjectSize = 4
                            )
                        )
                    ),
                    location = LiveSourceLocation(MaxObjectSizeControlTest::class.qualifiedName!!, 41),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doTest()

        errorOnTimeout(testContext)
    }
}