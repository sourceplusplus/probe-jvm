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

class LineNumberTest : ProbeIntegrationTest() {

    private fun doTest() {
        val a = 1
        val b = "test"
    }

    @Test
    fun testLineNumber(): Unit = runBlocking {
        val breakpointId = "line-number-test"
        val testContext = VertxTestContext()
        getLiveInstrumentSubscription(breakpointId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = event as LiveBreakpointHit
                    val vars = item.stackTrace.first().variables
                    assertEquals(3, vars.size)

                    assertEquals(35, vars.first { it.name == "a" }.lineNumber)
                    assertEquals(36, vars.first { it.name == "b" }.lineNumber)

                    testContext.completeNow()
                }
            }
        }

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = LineNumberTest::class.java.name,
                        line = 37,
                        service = "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = breakpointId
                )
            ).await()
        )

        log.info("Triggering breakpoint")
        doTest()

        errorOnTimeout(testContext)
    }
}
