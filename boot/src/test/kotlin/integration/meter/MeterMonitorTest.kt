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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.ViewRule

@Disabled
class MeterMonitorTest : ProbeIntegrationTest() {

    private fun doTest() {
        repeat(10) {
            val o = LifespanObject()
            Thread.sleep(100)
            println(o)

            System.gc()
        }
    }

    @Test
    fun `object lifespan count`(): Unit = runBlocking {
        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.OBJECT_LIFESPAN, "0"),
            location = LiveSourceLocation(
                LifespanObject::class.java.name + ".<init>(...)",
                service = "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true,
            meta = mapOf("metric.mode" to "RATE")
        )

        viewService.saveRuleIfAbsent(
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
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                assertEquals(1000.0, rawMetrics.getDouble("value"), 2000.0)
            }
            testContext.completeNow()
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        doTest()

        errorOnTimeout(testContext)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    @Test
    fun `object lifespan gauge`(): Unit = runBlocking {
        val liveMeter = LiveMeter(
            MeterType.GAUGE,
            MetricValue(MetricValueType.OBJECT_LIFESPAN, "0"),
            location = LiveSourceLocation(
                LifespanObject::class.java.name + ".<init>(...)",
                service = "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )

        viewService.saveRuleIfAbsent(
            ViewRule(
                name = liveMeter.id!!,
                exp = buildString {
                    append("(")
                    append(liveMeter.id)
                    append(".downsampling(LATEST)")
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
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                assertEquals(1000.0, rawMetrics.getDouble("value"), 1500.0)
            }
            testContext.completeNow()
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        doTest()

        errorOnTimeout(testContext)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    @Test
    fun `average object lifespan`(): Unit = runBlocking {
        val constructionCountMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LifespanObject::class.java.name + ".<init>(...)",
                service = "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true,
            meta = mapOf("metric.mode" to "RATE")
        )
        viewService.saveRule(
            ViewRule(
                name = constructionCountMeter.id!!,
                exp = buildString {
                    append("(")
                    append(constructionCountMeter.id)
                    append(".sum(['service', 'instance'])")
                    append(".downsampling(SUM)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                },
                meterIds = listOf(constructionCountMeter.id!!)
            )
        ).await()
        instrumentService.addLiveInstrument(constructionCountMeter).await()

        val lifespanTotalMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.OBJECT_LIFESPAN, "0"),
            location = LiveSourceLocation(
                LifespanObject::class.java.name + ".<init>(...)",
                service = "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true,
            meta = mapOf("metric.mode" to "RATE")
        )
        viewService.saveRule(
            ViewRule(
                name = lifespanTotalMeter.id!!,
                exp = buildString {
                    append("(")
                    append(lifespanTotalMeter.id)
                    append(".sum(['service', 'instance'])")
                    append(".downsampling(SUM)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                },
                meterIds = listOf(lifespanTotalMeter.id!!)
            )
        ).await()
        instrumentService.addLiveInstrument(lifespanTotalMeter).await()

        val avgMeterId = testNameAsUniqueInstrumentId
        viewService.saveRule(
            ViewRule(
                name = avgMeterId,
                exp = buildString {
                    append("(")
                    append(lifespanTotalMeter.id)
                    append("/")
                    append(constructionCountMeter.id)
                    append(").downsampling(LATEST)")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                },
                meterIds = listOf(lifespanTotalMeter.id!!, constructionCountMeter.id!!)
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(avgMeterId),
                viewConfig = LiveViewConfig("test", listOf(avgMeterId))
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        getLiveViewSubscription(subscriptionId).handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(avgMeterId, meta.getString("metricsName"))

                assertTrue(rawMetrics.getDouble("value") > 0.0)
                assertEquals(100.0, rawMetrics.getDouble("value"), 250.0)
            }
            testContext.completeNow()
        }

        doTest()

        errorOnTimeout(testContext)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(constructionCountMeter.id!!).await())
        assertNotNull(instrumentService.removeLiveInstrument(lifespanTotalMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    private class LifespanObject
}
