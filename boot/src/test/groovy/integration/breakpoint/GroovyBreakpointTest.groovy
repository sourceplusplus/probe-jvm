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
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.throttle.InstrumentThrottle

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

class GroovyBreakpointTest extends ProbeIntegrationTest {

    private void doTest() {
        def a = null
        def b = 1
        def c = 'a'
        def d = "a"
        def e = true
        def f = 1.0
        def g = 1.0f
        def h = 1L
    }

    @Test
    void testGroovy() {
        def breakpointId = "groovy-breakpoint-test"
        def testContext = new VertxTestContext()
        getLiveInstrumentSubscription(breakpointId).handler {
            def body = it.body()
            testContext.verify {
                def event = LiveInstrumentEvent.fromJson(body)
                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    def item = event as LiveBreakpointHit
                    def vars = item.stackTrace.first().variables
                    assertEquals(8, vars.size())

                    assertNotNull(vars.stream().find { it.name == "a" })
                    assertNotNull(vars.stream().find { it.name == "b" })
                    assertNotNull(vars.stream().find { it.name == "c" })
                    assertNotNull(vars.stream().find { it.name == "d" })
                    assertNotNull(vars.stream().find { it.name == "e" })
                    assertNotNull(vars.stream().find { it.name == "f" })
                    assertNotNull(vars.stream().find { it.name == "g" })

                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveInstrument(
                new LiveBreakpoint(
                        null,
                        new LiveSourceLocation(
                                GroovyBreakpointTest.class.name,
                                42, //todo: breaks if bp on return
                                "spp-test-probe"
                        ),
                        null,
                        null,
                        1,
                        breakpointId,
                        true,
                        false,
                        false,
                        InstrumentThrottle.DEFAULT,
                        new HashMap<String, Object>()
                )
        ).onComplete {
            if (it.succeeded()) {
                log.info("Triggering breakpoint")
                doTest()
            } else {
                testContext.failNow(it.cause())
            }
        }

        errorOnTimeout(testContext, 2000)
    }
}
