/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package integration

import io.vertx.core.Promise
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.SourceMarkerServices.Provide
import spp.protocol.instrument.*
import spp.protocol.instrument.log.event.LiveLogHit
import java.util.concurrent.TimeUnit

class LiveLogTest : ProbeIntegrationTest() {

    @Test
    fun testPrimitives() = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.handler {
            testContext.verify {
                val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
                log.trace("Received event: $event")

                if (event.eventType == LiveInstrumentEventType.LOG_HIT) {
                    val item = Json.decodeValue(event.data, LiveLogHit::class.java)
                    assertEquals("1 a a", item.logResult.logs.first().toFormattedMessage())
                }
                consumer.unregister()
                testContext.completeNow()
            }
        }

        assertNotNull(instrumentService.addLiveInstrument(
            LiveLog(
                logFormat = "{} {} {}",
                logArguments = listOf("a", "b", "c"),
                location = LiveSourceLocation("VariableTests", 35),
                applyImmediately = true
            )
        ).await())

        callVariableTests()
        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw RuntimeException(testContext.causeOfFailure())
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
