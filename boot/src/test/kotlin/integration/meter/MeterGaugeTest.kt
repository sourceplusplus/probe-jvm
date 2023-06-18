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
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.ViewRule
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.*
import java.util.function.Supplier

@Execution(ExecutionMode.SAME_THREAD)
class MeterGaugeTest : ProbeIntegrationTest() {

    @Suppress("UNUSED_VARIABLE")
    private fun doTest() {
        var i = 11
    }

    @Test
    fun `number supplier gauge`(): Unit = runBlocking {
        val meterId = testNameAsUniqueInstrumentId

        val supplier = object : Supplier<Double>, Serializable {
            override fun get(): Double = System.currentTimeMillis().toDouble()
        }
        val encodedSupplier = Base64.getEncoder().encodeToString(ByteArrayOutputStream().run {
            ObjectOutputStream(this).apply { writeObject(supplier) }
            toByteArray()
        })

        val liveMeter = LiveMeter(
            MeterType.GAUGE,
            MetricValue(MetricValueType.NUMBER_SUPPLIER, encodedSupplier),
            location = LiveSourceLocation(
                MeterGaugeTest::class.java.name,
                46,
                "spp-test-probe"
            ),
            id = meterId,
            applyImmediately = true
        )

        viewService.saveRule(
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
                viewConfig = LiveViewConfig("test", listOf(liveMeter.id!!))
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        getLiveViewSubscription(subscriptionId).handler {
            log.info("Received live view event: $it")
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                //check within 5 seconds
                val suppliedTime = rawMetrics.getLong("value")
                assertEquals(System.currentTimeMillis().toDouble(), suppliedTime.toDouble(), 5000.0)
            }
            testContext.completeNow()
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        doTest()

        errorOnTimeout(testContext)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    @Test
    fun `number expression gauge`(): Unit = runBlocking {
        val meterId = testNameAsUniqueInstrumentId

        val liveMeter = LiveMeter(
            MeterType.GAUGE,
            MetricValue(MetricValueType.NUMBER_EXPRESSION, "localVariables[i]"),
            location = LiveSourceLocation(
                MeterGaugeTest::class.java.name,
                46,
                "spp-test-probe"
            ),
            id = meterId,
            applyImmediately = true
        )

        viewService.saveRule(
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
                viewConfig = LiveViewConfig("test", listOf(liveMeter.id!!))
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        getLiveViewSubscription(subscriptionId).handler {
            log.info("Received live view event: ${it.body()}")
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                val iValue = rawMetrics.getLong("value")
                assertEquals(iValue, 11)
            }
            testContext.completeNow()
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        doTest()

        errorOnTimeout(testContext)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }
}
