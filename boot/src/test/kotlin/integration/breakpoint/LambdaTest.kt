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
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.location.LocationScope
import spp.protocol.instrument.throttle.InstrumentThrottle
import java.util.concurrent.atomic.AtomicInteger

@Execution(ExecutionMode.SAME_THREAD)
class LambdaTest : ProbeIntegrationTest() {

    private fun doLambdaOnlyTest() {
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
    fun `lambda only test`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val instrumentId = "breakpoint-lambda-only"
        getLiveInstrumentSubscription(instrumentId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = event as LiveBreakpointHit
                    val vars = item.stackTrace.first().variables
                    assertEquals(5, vars.size)

                    assertEquals(1, vars.first { it.name == "a" }.value)
                    assertEquals(2.0, vars.first { it.name == "b" }.value)
                    assertEquals("3", vars.first { it.name == "c" }.value)
                    //assertEquals(true, vars.first { it.name == "d" }.value) //todo: this is not working

                    testContext.completeNow()
                }
            }
        }

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = LambdaTest::class.java.name,
                        line = 46,
                        scope = LocationScope.LAMBDA,
                        service = "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = instrumentId
                )
            ).await()
        )

        //trigger breakpoint
        doLambdaOnlyTest()

        errorOnTimeout(testContext)
    }

    private fun doSameLineOnlyTest() {
        val a = 5
        val lambda = { x: Int -> println("$x") }
        lambda(a)
    }

    @Test
    fun `same line only test`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val instrumentId = "breakpoint-same-line-only"
        getLiveInstrumentSubscription(instrumentId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = event as LiveBreakpointHit
                    val vars = item.stackTrace.first().variables
                    assertEquals(2, vars.size)
                    assertEquals(5, vars.first { it.name == "a" }.value)
                    testContext.completeNow()
                }
            }
        }

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = LambdaTest::class.java.name,
                        line = 96,
                        scope = LocationScope.LINE,
                        service = "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = instrumentId
                )
            ).await()
        )

        //trigger breakpoint
        doSameLineOnlyTest()

        errorOnTimeout(testContext)
    }

    private fun doSameLineLambdaTest() {
        val a = 5
        val lambda = { x: Int -> println("$x") }
        lambda(a)
    }

    @Test
    fun `same line lambda test`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val instrumentId = "breakpoint-same-line-lambda"
        getLiveInstrumentSubscription(instrumentId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = event as LiveBreakpointHit
                    val vars = item.stackTrace.first().variables
                    assertEquals(3, vars.size)
                    assertEquals(5, vars.first { it.name == "p1" }.value) //todo: why p1? below test gets 'x'
                    testContext.completeNow()
                }
            }
        }

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = LambdaTest::class.java.name,
                        line = 140,
                        scope = LocationScope.LAMBDA,
                        service = "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = instrumentId
                )
            ).await()
        )

        //trigger breakpoint
        doSameLineLambdaTest()

        errorOnTimeout(testContext)
    }

    private fun doLambdaAndLineTest() {
        val a = 5
        val lambda = { x: Any? -> x }
        lambda(a)
    }

    @Test
    fun `lambda and line test`(): Unit = runBlocking {
        val hitCount = AtomicInteger(0)
        val testContext = VertxTestContext()
        val instrumentId = "breakpoint-lambda-and-line"
        getLiveInstrumentSubscription(instrumentId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = event as LiveBreakpointHit
                    val vars = item.stackTrace.first().variables
                    assertTrue(vars.any { it.name == "a" || it.name == "x" })

                    if (hitCount.incrementAndGet() == 2) {
                        testContext.completeNow()
                    }
                }
            }
        }

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = LambdaTest::class.java.name,
                        line = 184,
                        scope = LocationScope.BOTH,
                        service = "spp-test-probe"
                    ),
                    throttle = InstrumentThrottle.NONE,
                    applyImmediately = true,
                    hitLimit = 2,
                    id = instrumentId
                )
            ).await()
        )

        //trigger breakpoint
        doLambdaAndLineTest()

        errorOnTimeout(testContext)

        //todo: hit limit should take care of this automatically
        assertNotNull(instrumentService.removeLiveInstrument(instrumentId).await())
    }
}
