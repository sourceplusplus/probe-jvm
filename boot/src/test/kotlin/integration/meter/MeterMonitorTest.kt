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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscriberAddress
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.LiveViewRule

class MeterMonitorTest : ProbeIntegrationTest() {

    private fun doTest() {
        repeat(10) {
            val o = LifespanObject()
            Thread.sleep(100)
            println(o)

            System.gc()
        }
    }

    @Disabled
    @Test
    fun `object lifespan count`(): Unit = runBlocking {
        val meterId = "object-lifespan-count-" + System.currentTimeMillis()

        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.OBJECT_LIFESPAN, "0"),
            location = LiveSourceLocation(
                LifespanObject::class.java.name,
                service = "spp-test-probe"
            ),
            id = meterId,
            applyImmediately = true
        )

        viewService.saveRuleIfAbsent(
            LiveViewRule(
                name = liveMeter.toMetricIdWithoutPrefix(),
                exp = buildString {
                    append("(")
                    append(liveMeter.toMetricIdWithoutPrefix())
                    append(".sum(['service', 'instance'])")
                    append(".downsampling(SUM)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                }
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.toMetricId()),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter.toMetricId())
                )
            )
        ).await().subscriptionId!!
        val consumer = vertx.eventBus().localConsumer<JsonObject>(
            toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.toMetricId(), meta.getString("metricsName"))

                assertEquals(1000.0, rawMetrics.getDouble("value"), 2000.0)
            }
            testContext.completeNow()
        }.completionHandler().await()

        instrumentService.addLiveInstrument(liveMeter).await()

        doTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    @Disabled
    @Test
    fun `object lifespan gauge`(): Unit = runBlocking {
        val meterId = "object-lifespan-gauge"

        val liveMeter = LiveMeter(
            MeterType.GAUGE,
            MetricValue(MetricValueType.OBJECT_LIFESPAN, "0"),
            location = LiveSourceLocation(
                LifespanObject::class.java.name,
                service = "spp-test-probe"
            ),
            id = meterId,
            applyImmediately = true
        )

        viewService.saveRuleIfAbsent(
            LiveViewRule(
                name = liveMeter.toMetricIdWithoutPrefix(),
                exp = buildString {
                    append("(")
                    append(liveMeter.toMetricIdWithoutPrefix())
                    append(".downsampling(LATEST)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                }
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.toMetricId()),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter.toMetricId())
                )
            )
        ).await().subscriptionId!!
        val consumer = vertx.eventBus().localConsumer<JsonObject>(
            toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.toMetricId(), meta.getString("metricsName"))

                assertEquals(1000.0, rawMetrics.getDouble("value"), 1500.0)
            }
            testContext.completeNow()
        }.completionHandler().await()

        instrumentService.addLiveInstrument(liveMeter).await()

        doTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    @Test
    fun `average object lifespan`(): Unit = runBlocking {
        val countMeterId = "lifespan-object-gc-count"
        val countMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LifespanObject::class.java.name + ".<init>()",
                service = "spp-test-probe"
            ),
            id = countMeterId,
            applyImmediately = true,
            meta = mapOf("metric.mode" to "RATE")
        )
        viewService.saveRuleIfAbsent(
            LiveViewRule(
                name = countMeter.toMetricIdWithoutPrefix(),
                exp = buildString {
                    append("(")
                    append(countMeter.toMetricIdWithoutPrefix())
                    append(".sum(['service', 'instance'])")
                    append(".downsampling(SUM)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                }
            )
        ).await()
        instrumentService.addLiveInstrument(countMeter).await()

        val meterId = "lifespan-object-total-time-" + System.currentTimeMillis()
        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.OBJECT_LIFESPAN, "0"),
            location = LiveSourceLocation(
                LifespanObject::class.java.name,
                service = "spp-test-probe"
            ),
            id = meterId,
            applyImmediately = true,
            meta = mapOf("metric.mode" to "RATE")
        )
        viewService.saveRuleIfAbsent(
            LiveViewRule(
                name = liveMeter.toMetricIdWithoutPrefix(),
                exp = buildString {
                    append("(")
                    append(liveMeter.toMetricIdWithoutPrefix())
                    append(".sum(['service', 'instance'])")
                    append(".downsampling(SUM)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                }
            )
        ).await()
        instrumentService.addLiveInstrument(liveMeter).await()

        val avgMeterId = ("lifespan-object-avg-time-" + System.currentTimeMillis()).replace("-", "_")
        viewService.saveRuleIfAbsent(
            LiveViewRule(
                name = avgMeterId,
                exp = buildString {
                    append("(")
                    append(liveMeter.toMetricIdWithoutPrefix())
                    append("/")
                    append(countMeter.toMetricIdWithoutPrefix())
                    append(").downsampling(LATEST)")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                }
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf("spp_$avgMeterId"),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf("spp_$avgMeterId")
                )
            )
        ).await().subscriptionId!!
        val consumer = vertx.eventBus().localConsumer<JsonObject>(
            toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals("spp_$avgMeterId", meta.getString("metricsName"))

                assertEquals(100.0, rawMetrics.getDouble("value"), 200.0)
            }
            testContext.completeNow()
        }.completionHandler().await()

        doTest()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    private class LifespanObject
}
