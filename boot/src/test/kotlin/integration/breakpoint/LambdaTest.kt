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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.location.LocationScope
import spp.protocol.instrument.throttle.InstrumentThrottle
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
                    location = LiveSourceLocation(
                        source = LambdaTest::class.java.name,
                        line = 47,
                        scope = LocationScope.LAMBDA
                    ),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doLambdaOnlyTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister().await()
    }

    private fun doSameLineOnlyTest() {
        val a = 5
        val lambda = { x: Int -> println("$x") }
        lambda(a)
    }

    @Test
    fun `same line only test`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = LiveBreakpointHit(JsonObject(event.data))
                    val vars = item.stackTrace.first().variables
                    assertEquals(2, vars.size)
                    assertEquals(5, vars.first { it.name == "a" }.value)
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = LambdaTest::class.java.name,
                        line = 98,
                        scope = LocationScope.LINE
                    ),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doSameLineOnlyTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister().await()
    }

    private fun doSameLineLambdaTest() {
        val a = 5
        val lambda = { x: Int -> println("$x") }
        lambda(a)
    }

    @Test
    fun `same line lambda test`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = LiveBreakpointHit(JsonObject(event.data))
                    val vars = item.stackTrace.first().variables
                    assertEquals(3, vars.size)
                    assertEquals(5, vars.first { it.name == "p1" }.value)
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = LambdaTest::class.java.name,
                        line = 143,
                        scope = LocationScope.LAMBDA
                    ),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger breakpoint
        doSameLineLambdaTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister().await()
    }

    private fun doLambdaAndLineTest() {
        val a = 5
        val lambda = { x: Any? -> x }
        lambda(a)
    }

    @Test
    fun `lambda and line test`(): Unit = runBlocking {
        val gotAllHitsLatch = CountDownLatch(2)
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = LiveBreakpointHit(JsonObject(event.data))
                    val vars = item.stackTrace.first().variables
                    assertTrue(vars.any { it.name == "a" || it.name == "p1" })

                    gotAllHitsLatch.countDown()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = LambdaTest::class.java.name,
                        line = 188,
                        scope = LocationScope.BOTH
                    ),
                    throttle = InstrumentThrottle.NONE,
                    applyImmediately = true,
                    hitLimit = 5
                )
            ).await()
        )

        //trigger breakpoint
        doLambdaAndLineTest()

        withContext(Dispatchers.IO) {
            if (!gotAllHitsLatch.await(20, TimeUnit.SECONDS)) {
                testContext.failNow(RuntimeException("didn't get all hits"))
            }
        }
        testContext.completeNow()

        //clean up
        consumer.unregister().await()
    }
}
