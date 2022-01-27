package test

import integration.ProbeIntegrationTest.Companion.SYSTEM_JWT_TOKEN
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameParser
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import io.vertx.serviceproxy.ServiceProxyBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.protocol.ProtocolMarshaller
import spp.protocol.SourceMarkerServices
import spp.protocol.extend.TCPServiceFrameParser
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.LiveInstrumentEvent
import spp.protocol.instrument.LiveInstrumentEventType
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.breakpoint.event.LiveBreakpointHit
import spp.protocol.service.live.LiveInstrumentService
import java.util.*
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class LiveBreakpointTest {

    private fun setupHandler(socket: NetSocket, vertx: Vertx, address: String) {
        vertx.eventBus().localConsumer<JsonObject>(address) { resp ->
            val replyAddress = UUID.randomUUID().toString()
            val tempConsumer = vertx.eventBus().localConsumer<Any>("local.$replyAddress")
            tempConsumer.handler {
                resp.reply(it.body())
                tempConsumer.unregister()
            }

            val headers = JsonObject()
            resp.headers().entries().forEach { headers.put(it.key, it.value) }
            FrameHelper.sendFrame(
                BridgeEventType.SEND.name.toLowerCase(),
                address, replyAddress, headers, true, resp.body(), socket
            )
        }
    }

    private suspend fun setupTcp(vertx: Vertx): NetSocket {
        val serviceHost = if (System.getenv("SPP_PLATFORM_HOST") != null)
            System.getenv("SPP_PLATFORM_HOST") else "localhost"
        val options = NetClientOptions()
            .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
            .setSsl(true)
            .setTrustAll(true)
        val tcpSocket = withTimeout(5000) {
            vertx.createNetClient(options).connect(5455, serviceHost).await()
        }
        return tcpSocket
    }

    @Test
    fun testPrimitives() = runBlocking {
        val vertx = Vertx.vertx()
        val testContext = VertxTestContext()
        ProtocolMarshaller.setupCodecs(vertx)

        val socket = setupTcp(vertx)
        socket.handler(FrameParser(TCPServiceFrameParser(vertx!!, socket)))
        setupHandler(socket, vertx, SourceMarkerServices.Utilize.LIVE_INSTRUMENT)

        FrameHelper.sendFrame(
            BridgeEventType.REGISTER.name.toLowerCase(),
            SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
            JsonObject(),
            socket
        )

        val consumer = vertx.eventBus()
            .localConsumer<JsonObject>("local." + SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.handler {
            val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                val item = Json.decodeValue(event.data, LiveBreakpointHit::class.java)
                testContext.verify {
                    val vars = item.stackTrace.first().variables
                    assertEquals(9, vars.size)
                }
                testContext.completeNow()
            }
        }

        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        val promise = Promise.promise<LiveInstrument>()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation("VariableTests", 34),
                applyImmediately = true
            ), promise
        )
        val instrument = promise.future().await()
        assertNotNull(instrument)

        vertx.createHttpClient().request(HttpMethod.GET, 4000, "localhost", "/").await()

        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw RuntimeException(testContext.causeOfFailure())
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
