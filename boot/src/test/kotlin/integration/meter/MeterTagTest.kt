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
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.meter.*
import spp.protocol.service.SourceServices
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.LiveViewRule
import java.util.*
import java.util.concurrent.ThreadLocalRandom

class MeterTagTest : ProbeIntegrationTest() {

    private fun doTest() {
        var i = ThreadLocalRandom.current().nextBoolean()
    }

    @Test
    fun `test meter tags`(): Unit = runBlocking {
        instrumentService.clearAllLiveInstruments().await()

        val uuid = UUID.randomUUID().toString().replace("-", "")
        val meterId = "test-meter-tags-$uuid"
        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            meterTags = listOf(
                MeterTagValue(
                    "tag2",
                    MeterTagValueType.VALUE_EXPRESSION,
                    "localVariables[i]"
                )
            ),
            meta = mapOf("metric.mode" to "RATE"),
            location = LiveSourceLocation(
                MeterTagTest::class.qualifiedName!!,
                43,
                "spp-test-probe"
            ),
            id = meterId,
            applyImmediately = true,
            hitLimit = -1
        )

        val ruleName = "test_meter_tag_$uuid"
        viewService.saveRule(
            LiveViewRule(
                ruleName,
                "(${liveMeter.toMetricIdWithoutPrefix()}.sum(['service', 'tag2'])).service(['service'], Layer.GENERAL)"
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf("spp_$ruleName"),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf("spp_$ruleName")
                )
            )
        ).await().subscriptionId!!
        val consumer = vertx.eventBus().localConsumer<JsonObject>(
            SourceServices.Subscribe.toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            val summation = rawMetrics.getString("summation")
            log.debug("summation: $summation")

            val summationMap = summation.split("|").map { it.split(",") }.map { it[0] to it[1] }.toMap()
            val trueCount = summationMap["true"]!!.toInt()
            val falseCount = summationMap["false"]!!.toInt()
            if (trueCount + falseCount >= 10) {
                testContext.verify {
                    assertEquals(5.0, trueCount.toDouble(), 3.0)
                    assertEquals(5.0, falseCount.toDouble(), 3.0)
                    assertEquals(10, trueCount + falseCount)
                }
                testContext.completeNow()
            }
        }.completionHandler().await()

        assertNotNull(instrumentService.addLiveInstrument(liveMeter).await())
        delay(2500)

        for (i in 0 until 10) {
            doTest()
            delay(1000)
        }

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
        assertNotNull(viewService.deleteRule(ruleName).await())
    }
}
