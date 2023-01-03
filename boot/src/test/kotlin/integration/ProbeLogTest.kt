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
package integration

import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.event.LiveLogHit
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress

class ProbeLogTest : ProbeIntegrationTest() {

    private fun doTest() {
        val a = 1
        val b = 'a'
        val c = "a"
        val d = true
        val e = 1.0
        val f = 1.0f
        val g = 1L
        val h: Short = 1
        val i: Byte = 1
    }

    @Test
    fun testPrimitives() = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            testContext.verify {
                val event = LiveInstrumentEvent(it.body())
                if (event.eventType == LiveInstrumentEventType.LOG_HIT) {
                    val item = LiveLogHit(JsonObject(event.data))
                    assertEquals("1 a a", item.logResult.logs.first().toFormattedMessage())

                    consumer.unregister()
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveLog(
                    logFormat = "{} {} {}",
                    logArguments = listOf("a", "b", "c"),
                    location = LiveSourceLocation("integration.ProbeLogTest", 45),
                    applyImmediately = true
                )
            ).await()
        )

        //trigger log
        doTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
    }
}
