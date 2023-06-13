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
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.*
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.ViewRule

class MeterTagTest : ProbeIntegrationTest() {

    @Suppress("UNUSED_VARIABLE")
    private fun doTest(index: Int) {
        var i = index % 2 == 0
    }

    @Test
    fun `test meter tags`(): Unit = runBlocking {
        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            meterTags = listOf(
                MeterTag(
                    "tag2",
                    MeterValueType.VALUE_EXPRESSION,
                    "localVariables[i]"
                )
            ),
            meta = mapOf("metric.mode" to "RATE"),
            location = LiveSourceLocation(
                MeterTagTest::class.java.name,
                41,
                "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true,
            hitLimit = -1
        )

        viewService.saveRule(
            ViewRule(
                liveMeter.id!!,
                "(${liveMeter.id}.sum(['service', 'tag2']).downsampling(SUM)).service(['service'], Layer.GENERAL)",
                meterIds = listOf(liveMeter.id!!)
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.id!!),
                viewConfig = LiveViewConfig("test", listOf(liveMeter.id!!))
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        getLiveViewSubscription(subscriptionId).handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            val summation = rawMetrics.getJsonObject("value")
            log.info("summation: $summation")

            val trueCount = summation.getInteger("true")
            val falseCount = summation.getInteger("false")
            if (trueCount + falseCount >= 10) {
                testContext.verify {
                    assertEquals(5, trueCount)
                    assertEquals(5, falseCount)
                    assertEquals(10, trueCount + falseCount)
                }
                testContext.completeNow()
            }
        }

        assertNotNull(instrumentService.addLiveInstrument(liveMeter).await())

        repeat(10) {
            doTest(it)
            delay(1000)
        }

        errorOnTimeout(testContext, 30)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
        assertNotNull(viewService.deleteRule(liveMeter.id!!).await())
    }
}
