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
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.LiveInstrumentEvent
import spp.protocol.instrument.LiveInstrumentEventType
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.breakpoint.event.LiveBreakpointHit
import java.util.concurrent.TimeUnit

class LiveBreakpointTest : ProbeIntegrationTest() {

    @Test
    fun testPrimitives() = runBlocking {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.handler {
            testContext.verify {
                val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
                log.trace("Received event: $event")

                if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                    val item = Json.decodeValue(event.data, LiveBreakpointHit::class.java)
                    val vars = item.stackTrace.first().variables
                    assertEquals(9, vars.size)
                }
                consumer.unregister()
                testContext.completeNow()
            }
        }

        val promise = Promise.promise<LiveInstrument>()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation("VariableTests", 35),
                applyImmediately = true
            ), promise
        )
        assertNotNull(promise.future().await())

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
