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

class BreakpointReturnValueTest : ProbeIntegrationTest() {

    private fun doStringTest(): String {
        return "Hello World"
    }

    @Test
    fun `string return value`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = LiveBreakpointHit(JsonObject(event.data))
                    val vars = item.stackTrace.first().variables
                    assertEquals(2, vars.size)

                    //@return
                    val returnValue = vars.first { it.name == "@return" }
                    assertNotNull(returnValue)
                    assertEquals("Hello World", returnValue.value)

                    consumer.unregister()
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(BreakpointReturnValueTest::class.qualifiedName!!, 38),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doStringTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
    }

    private fun doIntegerTest(): Integer {
        return 200 as Integer
    }

    @Test
    fun `integer return value`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = LiveBreakpointHit(JsonObject(event.data))
                    val vars = item.stackTrace.first().variables
                    assertEquals(2, vars.size)

                    //@return
                    val returnValue = vars.first { it.name == "@return" }
                    assertNotNull(returnValue)
                    assertEquals(200, returnValue.value)

                    consumer.unregister()
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(BreakpointReturnValueTest::class.qualifiedName!!, 83),
                    applyImmediately = true
                )
            ).await()
        )

        log.info("Triggering breakpoint")
        doIntegerTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
    }

    private fun doIntTest(): Int {
        return 200
    }

    @Test
    fun `int return value`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = LiveBreakpointHit(JsonObject(event.data))
                    val vars = item.stackTrace.first().variables
                    assertEquals(2, vars.size)

                    //@return
                    val returnValue = vars.first { it.name == "@return" }
                    assertNotNull(returnValue)
                    assertEquals(200, returnValue.value)

                    consumer.unregister()
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(BreakpointReturnValueTest::class.qualifiedName!!, 128),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doIntTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
    }
}
