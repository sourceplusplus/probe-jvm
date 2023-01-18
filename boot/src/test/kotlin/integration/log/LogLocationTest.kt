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
package integration.log

import integration.ProbeIntegrationTest
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.event.LiveLogHit
import spp.protocol.instrument.location.LiveSourceLocation

class LogLocationTest : ProbeIntegrationTest() {

    private fun doTest() {
        var i = 0
    }

    @Test
    fun `log location`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val instrumentId = "log-location"
        getLiveInstrumentSubscription(instrumentId).handler {
            testContext.verify {
                val event = LiveInstrumentEvent.fromJson(it.body())
                if (event.eventType == LiveInstrumentEventType.LOG_HIT) {
                    val item = event as LiveLogHit
                    assertEquals(1, item.logResult.logs.size)

                    val location = item.logResult.logs.first().location
                    assertNotNull(location)
                    assertEquals(LogLocationTest::class.qualifiedName!!, location!!.source)
                    assertEquals(36, location.line)

                    testContext.completeNow()
                }
            }
        }

        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveLog(
                    "Hello World",
                    location = LiveSourceLocation(
                        source = LogLocationTest::class.java.name,
                        line = 36,
                        service = "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = instrumentId
                )
            ).await()
        )

        //trigger log
        doTest()

        errorOnTimeout(testContext)
    }
}
