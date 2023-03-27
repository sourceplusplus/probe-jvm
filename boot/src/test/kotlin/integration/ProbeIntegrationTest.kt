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
package integration

import io.vertx.core.*
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.RequestOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import io.vertx.serviceproxy.ServiceProxyBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.LiveViewService
import spp.protocol.service.SourceServices
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscription
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscriberAddress
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.service.extend.TCPServiceSocket
import java.util.*
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
abstract class ProbeIntegrationTest {

    val log: Logger by lazy { LoggerFactory.getLogger(this::class.java.name) }

    var testName: String? = null
    val testNameAsInstrumentId: String
        get() {
            return testName!!.replace(" ", "-").lowercase().substringBefore("(")
        }
    val testNameAsUniqueInstrumentId: String
        get() {
            return testNameAsInstrumentId + "-" + UUID.randomUUID().toString().replace("-", "")
        }

    @BeforeEach
    open fun setUp(testInfo: TestInfo) {
        testName = testInfo.displayName
    }

    companion object {
        lateinit var vertx: Vertx
        lateinit var instrumentService: LiveInstrumentService
        lateinit var viewService: LiveViewService
        private lateinit var socket: NetSocket
        private val serviceHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
        private const val servicePort = 12800
        private val accessToken: String? by lazy { fetchAccessToken() }

        @BeforeAll
        @JvmStatic
        fun setup() = runBlocking {
            vertx = Vertx.vertx()
            socket = setupTcp(vertx)
            TCPServiceSocket(vertx, socket)
            setupHandler(socket, vertx, SourceServices.LIVE_INSTRUMENT)
            setupHandler(socket, vertx, SourceServices.LIVE_VIEW)

            //setup connection
            val replyAddress = UUID.randomUUID().toString()
            val pc = InstanceConnection(UUID.randomUUID().toString(), System.currentTimeMillis())
            val consumer: MessageConsumer<Boolean> = vertx.eventBus().localConsumer(replyAddress)
            val headers = JsonObject()
            if (accessToken != null) {
                headers.put("auth-token", accessToken)
            }

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
                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.lowercase(),
                    toLiveViewSubscriberAddress("system"), null,
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
                .apply { accessToken?.let { setToken(it) } }
                .setAddress(SourceServices.LIVE_INSTRUMENT)
                .build(LiveInstrumentService::class.java)
            viewService = ServiceProxyBuilder(vertx)
                .apply { accessToken?.let { setToken(it) } }
                .setAddress(SourceServices.LIVE_VIEW)
                .build(LiveViewService::class.java)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            runBlocking {
                vertx.close().await()
            }
        }

        fun getLiveViewSubscription(viewId: String): MessageConsumer<JsonObject> {
            val listenAddress = toLiveViewSubscription(viewId)

            //send register
            val headers = JsonObject()
            if (accessToken != null) {
                headers.put("auth-token", accessToken)
            }
            FrameHelper.sendFrame(
                BridgeEventType.REGISTER.name.lowercase(),
                listenAddress, null,
                headers, null, null, socket
            )

            return vertx.eventBus().localConsumer(listenAddress)
        }

        @JvmStatic
        fun getLiveInstrumentSubscription(instrumentId: String): MessageConsumer<JsonObject> {
            val listenAddress = toLiveInstrumentSubscription(instrumentId)

            //send register
            val headers = JsonObject()
            if (accessToken != null) {
                headers.put("auth-token", accessToken)
            }
            FrameHelper.sendFrame(
                BridgeEventType.REGISTER.name.lowercase(),
                listenAddress, null,
                headers, null, null, socket
            )

            return vertx.eventBus().localConsumer(listenAddress)
        }

        private fun setupHandler(socket: NetSocket, vertx: Vertx, address: String) {
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
            val options = NetClientOptions()
                .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
            val tcpSocket = withTimeout(5000) {
                vertx.createNetClient(options).connect(servicePort, serviceHost).await()
            }
            return tcpSocket
        }

        private fun fetchAccessToken() = runBlocking {
            val tokenUri = "/api/new-token?authorization_code=change-me"
            val req = vertx.createHttpClient(HttpClientOptions())
                .request(
                    RequestOptions()
                        .setHost(serviceHost)
                        .setPort(servicePort)
                        .setURI(tokenUri)
                ).await()
            req.end().await()
            val resp = req.response().await()
            if (resp.statusCode() == 200) {
                resp.body().await().toString()
            } else {
                null
            }
        }
    }

    fun errorOnTimeout(testContext: VertxTestContext, waitTime: Long = 20) {
        if (testContext.awaitCompletion(waitTime, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    private val lineLabels = mutableMapOf<String, Int>()
    private var setupLineLabels = false

    fun addLineLabel(label: String, getLineNumber: () -> Int) {
        lineLabels[label] = getLineNumber.invoke()
    }

    fun getLineNumber(label: String): Int {
        return lineLabels[label] ?: throw IllegalArgumentException("No line label found for $label")
    }

    fun setupLineLabels(invoke: () -> Unit) {
        setupLineLabels = true
        invoke.invoke()
        setupLineLabels = false
    }
}
