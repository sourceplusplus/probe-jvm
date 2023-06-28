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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.platform.general.Service
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.ViewRule

class MeterConstructorCountTest : ProbeIntegrationTest() {

    private fun doTest() {
        repeat(10) {
            MyObject()
        }
    }

    @Test
    fun `constructor count`(): Unit = runBlocking {
        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                MyObject::class.java.name + ".<init>()",
                service = Service.fromName("spp-test-probe")
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true,
            meta = mapOf("metric.mode" to "RATE")
        )

        viewService.saveRule(
            ViewRule(
                name = liveMeter.id!!,
                exp = buildString {
                    append("(")
                    append(liveMeter.id)
                    append(".sum(['service', 'instance'])")
                    append(".downsampling(SUM)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                },
                meterIds = listOf(liveMeter.id!!)
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.id!!),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter.id!!)
                )
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        getLiveViewSubscription(subscriptionId).handler {
            log.info("Live view event: ${it.body()}")
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                assertEquals(10, rawMetrics.getLong("value"))
            }
            testContext.completeNow()
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        log.info("Triggering meter")
        doTest()

        errorOnTimeout(testContext)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    private class MyObject
}
