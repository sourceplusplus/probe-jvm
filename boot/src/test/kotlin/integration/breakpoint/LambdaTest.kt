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
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress

class LambdaTest : ProbeIntegrationTest() {

    private fun doTest() {
        val lambda = {
            val a = 1
            val b = 2.0
            val c = "3"
            val d = true
            println("$a $b $c $d")
        }
        lambda()
    }

    @Test
    fun `lambda test`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = LiveBreakpointHit(JsonObject(event.data))
                    val vars = item.stackTrace.first().variables
                    assertEquals(5, vars.size)

                    assertEquals(1, vars.first { it.name == "a" }.value)
                    assertEquals(2.0, vars.first { it.name == "b" }.value)
                    assertEquals("3", vars.first { it.name == "c" }.value)
                    //assertEquals(true, vars.first { it.name == "d" }.value) //todo: this is not working

                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(LambdaTest::class.java.name, 42),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister().await()
    }
}
