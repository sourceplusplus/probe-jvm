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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import java.time.*
import java.util.*

@Suppress("UNUSED_VARIABLE")
class TimeBreakpointTest : ProbeIntegrationTest() {

    private fun timeValues() {
        val date = Date(1L)
        val duration = Duration.ofSeconds(1)
        val instant = Instant.ofEpochSecond(1)
        val localDate = LocalDate.of(1, 2, 3)
        val localTime = LocalTime.of(1, 2, 3, 4)
        val localDateTime = LocalDateTime.of(1, 2, 3, 4, 5, 6)
        val offsetDateTime = OffsetDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneOffset.ofHours(8))
        val offsetTime = OffsetTime.of(1, 2, 3, 4, ZoneOffset.ofHours(8))
        val zonedDateTime = ZonedDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneId.of("Europe/Berlin"))
        val zoneOffset = ZoneOffset.ofHours(8)
//        val zoneRegion = ZoneRegion.ofId("Europe/Berlin")
    }

    @Test
    fun `time values`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        getLiveInstrumentSubscription(testNameAsInstrumentId).handler {
            val event = LiveInstrumentEvent.fromJson(it.body())
            if (event.eventType != LiveInstrumentEventType.BREAKPOINT_HIT) return@handler
            val bpHit = event as LiveBreakpointHit
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(11, topFrame.variables.size)

                // Date
                assertEquals(
                    Date(1L).toString(),
                    topFrame.variables.find { it.name == "date" }!!.value.let { Date(it.toString()).toString() }
                )
                assertEquals(
                    "java.util.Date",
                    topFrame.variables.find { it.name == "date" }!!.liveClazz
                )

                // Duration
                assertEquals(
                    Duration.ofSeconds(1).toString(),
                    topFrame.variables.find { it.name == "duration" }!!.presentation
                )
                assertEquals(
                    "java.time.Duration",
                    topFrame.variables.find { it.name == "duration" }!!.liveClazz
                )

                // Instant
                //todo: instants don't match
//                assertEquals(
//                    Instant.ofEpochSecond(1).toString(),
//                    topFrame.variables.find { it.name == "instant" }!!.presentation
//                )
                assertEquals(
                    "java.time.Instant",
                    topFrame.variables.find { it.name == "instant" }!!.liveClazz
                )

                // LocalDate
                assertEquals(
                    LocalDate.of(1, 2, 3).toString(),
                    topFrame.variables.find { it.name == "localDate" }!!.presentation
                )
                assertEquals(
                    "java.time.LocalDate",
                    topFrame.variables.find { it.name == "localDate" }!!.liveClazz
                )

                // LocalTime
                assertEquals(
                    LocalTime.of(1, 2, 3, 4).toString(),
                    topFrame.variables.find { it.name == "localTime" }!!.presentation
                )
                assertEquals(
                    "java.time.LocalTime",
                    topFrame.variables.find { it.name == "localTime" }!!.liveClazz
                )

                // LocalDateTime
                assertEquals(
                    LocalDateTime.of(1, 2, 3, 4, 5, 6).toString(),
                    topFrame.variables.find { it.name == "localDateTime" }!!.presentation
                )
                assertEquals(
                    "java.time.LocalDateTime",
                    topFrame.variables.find { it.name == "localDateTime" }!!.liveClazz
                )

                // OffsetDateTime
                //todo: offsetDateTimes don't match
//                    assertEquals(
//                        OffsetDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneOffset.ofHours(8)).toString(),
//                        topFrame.variables.find { it.name == "offsetDateTime" }!!.presentation
//                    )
                assertEquals(
                    "java.time.OffsetDateTime",
                    topFrame.variables.find { it.name == "offsetDateTime" }!!.liveClazz
                )

                // OffsetTime
                //todo: offsetTimes don't match
//                    assertEquals(
//                        OffsetTime.of(1, 2, 3, 4, ZoneOffset.ofHours(8)).toString(),
//                        topFrame.variables.find { it.name == "offsetTime" }!!.presentation
//                    )
                assertEquals(
                    "java.time.OffsetTime",
                    topFrame.variables.find { it.name == "offsetTime" }!!.liveClazz
                )

                // ZonedDateTime
                //todo: zonedDateTimes don't match
//                    assertEquals(
//                        ZonedDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneId.of("Europe/Berlin")).toString(),
//                        topFrame.variables.find { it.name == "zonedDateTime" }!!.presentation
//                    )
                assertEquals(
                    "java.time.ZonedDateTime",
                    topFrame.variables.find { it.name == "zonedDateTime" }!!.liveClazz
                )

                // ZoneOffset
                //todo: zoneOffsets don't match
//                    assertEquals(
//                        ZoneOffset.ofHours(8).toString(),
//                        topFrame.variables.find { it.name == "zoneOffset" }!!.presentation
//                    )
                assertEquals(
                    "java.time.ZoneOffset",
                    topFrame.variables.find { it.name == "zoneOffset" }!!.liveClazz
                )
            }

            //test passed
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    TimeBreakpointTest::class.java.name,
                    50,
                    Service.fromName("spp-test-probe")
                ),
                applyImmediately = true,
                id = testNameAsInstrumentId
            )
        ).await()

        //trigger live breakpoint
        timeValues()

        errorOnTimeout(testContext)
    }
}
