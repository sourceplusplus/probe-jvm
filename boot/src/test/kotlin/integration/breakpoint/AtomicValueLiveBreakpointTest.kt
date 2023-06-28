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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.variable.LiveVariable
import spp.protocol.platform.general.Service
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("UNUSED_VARIABLE")
class AtomicValueLiveBreakpointTest : ProbeIntegrationTest() {

    private fun atomicValue() {
        val atomicMap = AtomicReference(mapOf("test" to "test"))
        val atomicString = AtomicReference<String>().apply { set("test") }
        val atomicInteger = AtomicInteger(1)
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun `atomic value`(): Unit = runBlocking {
        setupLineLabels {
            atomicValue()
        }

        val testContext = VertxTestContext()
        getLiveInstrumentSubscription(testNameAsInstrumentId).handler {
            val event = LiveInstrumentEvent.fromJson(it.body())
            if (event.eventType != LiveInstrumentEventType.BREAKPOINT_HIT) return@handler
            val bpHit = event as LiveBreakpointHit
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(4, topFrame.variables.size)

                //atomicMap
                val atomicMapVariable = topFrame.variables.first { it.name == "atomicMap" }
                assertNotNull(atomicMapVariable)
                assertEquals(
                    "java.util.concurrent.atomic.AtomicReference",
                    atomicMapVariable.liveClazz
                )
                assertNotNull(atomicMapVariable.liveIdentity)
                val atomicMapValue = LiveVariable(
                    JsonObject.mapFrom((atomicMapVariable.value as JsonArray).first())
                )
                assertNotNull(atomicMapValue)
                assertEquals(
                    "java.util.Collections\$SingletonMap",
                    atomicMapValue.liveClazz
                )
                val atomicMapFinalValue = LiveVariable(
                    JsonObject.mapFrom((atomicMapValue.value as JsonArray).first())
                )
                assertNotNull(atomicMapFinalValue)
                assertEquals("test", atomicMapFinalValue.name)
                assertEquals("test", atomicMapFinalValue.value)

                //atomicString
                val atomicStringVariable = topFrame.variables.first { it.name == "atomicString" }
                assertNotNull(atomicStringVariable)
                assertEquals(
                    "java.util.concurrent.atomic.AtomicReference",
                    atomicStringVariable.liveClazz
                )
                assertNotNull(atomicStringVariable.liveIdentity)
                val atomicStringValue = LiveVariable(
                    JsonObject.mapFrom((atomicStringVariable.value as JsonArray).first())
                )
                assertNotNull(atomicStringValue)
                assertEquals("test", atomicStringValue.value)

                //atomicInteger
                val atomicIntegerVariable = topFrame.variables.first { it.name == "atomicInteger" }
                assertNotNull(atomicIntegerVariable)
                assertEquals(
                    "java.util.concurrent.atomic.AtomicInteger",
                    atomicIntegerVariable.liveClazz
                )
                assertNotNull(atomicIntegerVariable.liveIdentity)
                assertEquals(1, atomicIntegerVariable.value)
            }

            //test passed
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    source = AtomicValueLiveBreakpointTest::class.java.name,
                    line = getLineNumber("done"),
                    service = Service.fromName("spp-test-probe")
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        atomicValue()

        errorOnTimeout(testContext)
    }
}
