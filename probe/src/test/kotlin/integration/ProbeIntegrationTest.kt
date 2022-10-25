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

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.eventbus.MessageConsumer
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.SourceServices
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
import spp.protocol.service.extend.TCPServiceFrameParser
import java.util.*
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
abstract class ProbeIntegrationTest {

    companion object {

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
            val headers = JsonObject()

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
            val tcpSocket = withTimeout(5000) {
                vertx.createNetClient(options).connect(12800, serviceHost).await()
            }
            return tcpSocket
        }
    }

    fun <T> MessageConsumer<T>.completionHandler(): Future<Void> {
        val promise = Promise.promise<Void>()
        completionHandler { promise.handle(it) }
        return promise.future()
    }

    fun errorOnTimeout(testContext: VertxTestContext, waitTime: Long = 15) {
        if (testContext.awaitCompletion(waitTime, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
