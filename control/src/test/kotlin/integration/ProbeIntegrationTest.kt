/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022 CodeBrig, Inc.
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
package integration

import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.eventbus.MessageConsumer
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.SourceServices
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
import spp.protocol.service.extend.TCPServiceFrameParser
import java.io.IOException
import java.util.*

@ExtendWith(VertxExtension::class)
abstract class ProbeIntegrationTest {

    companion object {

        val log: Logger = LoggerFactory.getLogger(ProbeIntegrationTest::class.java)
        const val SYSTEM_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJ0ZW5hbnRfaWQiOiJ0ZW5hbnQxIiwiZGV2ZWxvcGVyX2lkIjoic3lzdGVtIiwiY3JlY" +
                    "XRlZF9hdCI6MTY2MDM4NzgwNjI2MSwiZXhwaXJlc19hdCI6MTY5MTkyMzgwNjI2MSwiaWF0IjoxNjYwMzg3ODA2fQ.e0lWdOmF" +
                    "PY_Bi9tE4wp_SUmv4GKuMInrs8oWlacd5pvbGryAj4pFbOhstj0-Nc-DIXbuTp5VKTqSpwgSrFMtNjFTQTJ9q2182Wjlh7g19H" +
                    "ZipCL-Qjuy2Oh-hLkbm1KZKY8DymYKYkDUZjO8Owb97_BbBewKRCfCqqFKABnMcAhyOJ5PLs61xG0uBe9IVvjzlXz8Uwx9ObE1" +
                    "N74cDsm1arCbDyVnSYMOIm9ilOBjUynastAyybYEDRp5jjyXhHwzcZ4vlKp65AUd1Jw2QZBBgTCT-U01tIAjDujGejarEgcGed" +
                    "M31y44sS3kaJwHQIS01mb2RpaZQzMlp1HtBJS3QtT3M8OUDlOSnT4_cc0lp2y4xXI26W66q-M0KBlFisCJPR0lvP1njg_jMspy" +
                    "n0YBbu4zMEQtSy2L6NIMAaEj7lVLu_mMisaD33pbORW0QsGFjq-krPo6FfulCSYdxNNyUrlh93f6Qy3KQlM8Kp47INfoV6AJGc" +
                    "kpEGzeVrCiKCYq2MaCbENh2Eu4EpBkLawuBid8RgQ-Kp-tlv9rxYdbQp8R8HA4lF9bMK-hM3-akkWzENqEof6e_gE2xdk-e-6-" +
                    "jJGQpouEiVCaA81f4CswRYHdAVgsSwVfWEqP072CULplI_KhgFhQ1YBQ_ku_jxTkGSfvub2bkeAHZdqyrMQwu7o"

        lateinit var vertx: Vertx
        lateinit var instrumentService: LiveInstrumentService

        @BeforeAll
        @JvmStatic
        fun setup() = runBlocking {
            vertx = Vertx.vertx()

            val socket = setupTcp(vertx)
            socket.handler(FrameParser(TCPServiceFrameParser(vertx, socket)))
            setupHandler(socket, vertx, SourceServices.LIVE_INSTRUMENT)

            //setup connection
            val replyAddress = UUID.randomUUID().toString()
            val pc = InstanceConnection(UUID.randomUUID().toString(), System.currentTimeMillis())
            val consumer: MessageConsumer<Boolean> = vertx.eventBus().localConsumer(replyAddress)
            val headers = JsonObject().put("auth-token", SYSTEM_JWT_TOKEN)

            val promise = Promise.promise<Void>()
            consumer.handler {
                assertTrue(it.body())

                promise.complete()
                consumer.unregister()

                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.lowercase(),
                    toLiveInstrumentSubscriberAddress("system"), null,
                    headers, null, null, socket
                )
            }
            FrameHelper.sendFrame(
                BridgeEventType.SEND.name.lowercase(),
                PlatformAddress.MARKER_CONNECTED,
                replyAddress, headers, true, JsonObject.mapFrom(pc), socket
            )

            promise.future().await()
            instrumentService = ServiceProxyBuilder(vertx)
                .setToken(SYSTEM_JWT_TOKEN)
                .setAddress(SourceServices.LIVE_INSTRUMENT)
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
                val tempConsumer = vertx.eventBus().localConsumer<Any>(replyAddress)
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

        private suspend fun setupTcp(vertx: Vertx): NetSocket {
            val serviceHost = if (System.getenv("SPP_PLATFORM_HOST") != null)
                System.getenv("SPP_PLATFORM_HOST") else "localhost"
            val options = NetClientOptions()
                .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
                .setSsl(true)
                .setTrustAll(true)
            val tcpSocket = withTimeout(5000) {
                vertx.createNetClient(options).connect(12800, serviceHost).await()
            }
            return tcpSocket
        }

        suspend fun callVariableTests() {
            val e2eAppHost = if (System.getenv("E2E_APP_HOST") != null)
                System.getenv("E2E_APP_HOST") else "localhost"
            log.trace("E2E_APP_HOST: $e2eAppHost")

            try {
                val statusCode = WebClient.create(vertx).get(4000, e2eAppHost, "/").send().await().statusCode()
                log.trace("Status code: $statusCode")
                assertEquals(200, statusCode)
            } catch (_: IOException) {
            }
        }
    }
}
