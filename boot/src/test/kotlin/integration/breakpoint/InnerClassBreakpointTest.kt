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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.variable.LiveVariableScope
import spp.protocol.platform.general.Service

@Suppress("UNUSED_VARIABLE")
class InnerClassBreakpointTest : ProbeIntegrationTest() {

    inner class InnerClass {
        fun doHit() {
            val myVar = 10
            addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        }
    }

    @Test
    fun `inner class bp`() = runBlocking {
        setupLineLabels {
            InnerClass().doHit()
        }

        val testContext = VertxTestContext()
        val breakpointId = testNameAsUniqueInstrumentId
        getLiveInstrumentSubscription(breakpointId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val bpHit = event as LiveBreakpointHit
                    assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                    val topFrame = bpHit.stackTrace.elements.first()
                    assertEquals(2, topFrame.variables.size)

                    val myVar = topFrame.variables.first { it.name == "myVar" }
                    assertEquals("myVar", myVar.name)
                    assertEquals(10, myVar.value)
                    assertEquals("java.lang.Integer", myVar.liveClazz)
                    assertEquals(LiveVariableScope.LOCAL_VARIABLE, myVar.scope)
                    assertNotNull(myVar.liveIdentity)
                }
            }

            //test passed
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    InnerClass::class.java.name,
                    getLineNumber("done"),
                    Service.fromName("spp-test-probe")
                ),
                applyImmediately = true,
                id = breakpointId
            )
        ).await()

        //trigger live breakpoint
        InnerClass().doHit()

        errorOnTimeout(testContext)
    }
}
