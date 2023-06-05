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
package integration.meter

import integration.ProbeIntegrationTest
import io.vertx.core.json.JsonArray
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.MethodTimerAvgRule
import spp.protocol.view.rule.MethodTimerCountRule

class MeterMethodTimerTest : ProbeIntegrationTest() {

    private fun doTest() {
        Thread.sleep(200)
    }

    @Test
    fun `method timer meter`(): Unit = runBlocking {
        val liveMeter = LiveMeter(
            MeterType.METHOD_TIMER,
            MetricValue(MetricValueType.NUMBER, ""),
            location = LiveSourceLocation(
                MeterMethodTimerTest::class.java.name + ".doTest()",
                service = "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )

        viewService.saveRule(MethodTimerAvgRule(liveMeter)).await()
        viewService.saveRule(MethodTimerCountRule(liveMeter)).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(
                    liveMeter.id!! + "_avg",
                    liveMeter.id!! + "_count"
                ),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(
                        liveMeter.id!! + "_avg",
                        liveMeter.id!! + "_count"
                    )
                )
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        getLiveViewSubscription(subscriptionId).handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonArray(liveViewEvent.metricsData)
            testContext.verify {
                val avg = rawMetrics.getJsonObject(0)
                assertTrue(avg.getString("metric_type").endsWith("_avg"))
                assertEquals(200.0, avg.getDouble("value"), 5.0)

                val rate = rawMetrics.getJsonObject(1)
                assertEquals(10.0, rate.getDouble("summation"))
            }
            testContext.completeNow()
        }

        assertNotNull(instrumentService.addLiveInstrument(liveMeter).await())

        for (i in 0 until 10) {
            doTest()
        }

        errorOnTimeout(testContext)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }
}
