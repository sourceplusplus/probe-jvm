package integration

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameParser
import io.vertx.ext.web.client.WebClient
import io.vertx.junit5.VertxExtension
import io.vertx.kotlin.coroutines.await
import io.vertx.serviceproxy.ServiceProxyBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import spp.protocol.ProtocolMarshaller
import spp.protocol.SourceMarkerServices
import spp.protocol.extend.TCPServiceFrameParser
import spp.protocol.service.live.LiveInstrumentService
import java.util.*

@ExtendWith(VertxExtension::class)
abstract class ProbeTest {

    companion object {
        lateinit var vertx: Vertx
        lateinit var instrumentService: LiveInstrumentService

        @BeforeAll
        @JvmStatic
        fun setup() = runBlocking {
            vertx = Vertx.vertx()
            ProtocolMarshaller.setupCodecs(vertx)

            val socket = setupTcp(vertx)
            socket.handler(FrameParser(TCPServiceFrameParser(vertx, socket)))
            setupHandler(socket, vertx, SourceMarkerServices.Utilize.LIVE_INSTRUMENT)

            FrameHelper.sendFrame(
                BridgeEventType.REGISTER.name.lowercase(),
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject(),
                socket
            )

            instrumentService = ServiceProxyBuilder(vertx)
                .setToken(ProbeIntegrationTest.SYSTEM_JWT_TOKEN)
                .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
                .build(LiveInstrumentService::class.java)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            runBlocking {
                vertx.close().await()
            }
        }

        fun setupHandler(socket: NetSocket, vertx: Vertx, address: String) {
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
                    BridgeEventType.SEND.name.lowercase(),
                    address, replyAddress, headers, true, resp.body(), socket
                )
            }
        }

        suspend fun setupTcp(vertx: Vertx): NetSocket {
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

        suspend fun callVariableTests() {
            val e2eAppHost = if (System.getenv("E2E_APP_HOST") != null)
                System.getenv("E2E_APP_HOST") else "localhost"

            assertEquals(
                200,
                WebClient.create(vertx).get(4000, e2eAppHost, "/").send().await().statusCode()
            )
        }
    }
}
