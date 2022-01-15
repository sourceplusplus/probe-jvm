package integration

import io.vertx.core.AsyncResult
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.probe.ProbeConfiguration.setQuietMode
import spp.probe.ProbeConfiguration.setString
import spp.probe.SourceProbe
import spp.probe.SourceProbe.PROBE_ID
import spp.probe.SourceProbe.connectToPlatform
import spp.probe.SourceProbe.disconnectFromPlatform
import spp.probe.SourceProbe.isAgentInitialized
import spp.probe.SourceProbe.vertx
import spp.protocol.probe.ProbeAddress
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@ExtendWith(VertxExtension::class)
class ProbeIntegrationTest {

    @Test
    fun verifyRemotesRegistered() {
        val testContext = VertxTestContext()
        assertTrue(isAgentInitialized)
        setQuietMode(false)
        val platformHost = if (System.getenv("SPP_PLATFORM_HOST") != null)
            System.getenv("SPP_PLATFORM_HOST") else "localhost"
        setString("platform_host", platformHost)
        val client = WebClient.create(
            vertx, WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
        )
        client[5445, platformHost, "/stats"]
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN).send().onComplete { it: AsyncResult<HttpResponse<Buffer?>> ->
                if (it.succeeded()) {
                    val result = it.result().bodyAsJsonObject().getJsonObject("platform")
                    testContext.verify {
                        val probeCount = result.getInteger("connected-probes")
                        assertNotEquals(0, probeCount)
                        val services = result.getJsonObject("services")
                        services.getJsonObject("probe").map.forEach {
                            assertEquals(probeCount, it.value)
                        }
                    }
                    client.close()
                    testContext.completeNow()
                } else {
                    testContext.failNow(it.cause())
                }
            }
        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw RuntimeException(testContext.causeOfFailure())
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun verifyClientsConnected() {
        val testContext = VertxTestContext()
        assertTrue(isAgentInitialized)
        setQuietMode(false)
        val platformHost = if (System.getenv("SPP_PLATFORM_HOST") != null)
            System.getenv("SPP_PLATFORM_HOST") else "localhost"
        setString("platform_host", platformHost)
        val client = WebClient.create(
            vertx, WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
        )
        client[5445, platformHost, "/clients"]
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN).send().onComplete { it: AsyncResult<HttpResponse<Buffer?>> ->
                if (it.succeeded()) {
                    val result = it.result().bodyAsJsonObject()
                    val processors = result.getJsonArray("processors")
                    val markers = result.getJsonArray("markers")
                    val probes = result.getJsonArray("probes")
                    testContext.verify {
                        assertNotEquals(0, processors.size())
                        assertEquals(0, markers.size())
                        assertNotEquals(0, probes.size())
                    }
                    client.close()
                    testContext.completeNow()
                } else {
                    testContext.failNow(it.cause())
                }
            }
        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw RuntimeException(testContext.causeOfFailure())
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun receivePendingInstrumentsOnReconnect() {
        val testContext = VertxTestContext()
        assertTrue(isAgentInitialized)
        setQuietMode(false)
        SourceProbe.tcpSocket!!.closeHandler {
            log.info("Disconnected from platform")
            val platformHost = if (System.getenv("SPP_PLATFORM_HOST") != null)
                System.getenv("SPP_PLATFORM_HOST") else "localhost"
            setString("platform_host", platformHost)
            val client = WebClient.create(
                vertx, WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
            )
            val unregistered = AtomicBoolean(false)
            val consumer: MessageConsumer<JsonObject> = vertx!!.eventBus()
                .localConsumer("local." + ProbeAddress.LIVE_LOG_REMOTE.address + ":" + PROBE_ID)
            consumer.handler {
                log.info("Got command: {}", it.body())
                if (unregistered.get()) {
                    log.warn("Ignoring message after unregistered...")
                    return@handler
                }
                val (commandType, context) = Json.decodeValue(it.body().toString(), LiveInstrumentCommand::class.java)
                testContext.verify {
                    assertEquals(CommandType.ADD_LIVE_INSTRUMENT, commandType)
                    assertEquals(1, context.liveInstruments.size)
                    val liveLog = JsonObject(context.liveInstruments[0])
                    assertEquals("test", liveLog.getString("logFormat"))
                }
                consumer.unregister { it3: AsyncResult<Void?> ->
                    if (it3.succeeded()) {
                        log.info("Unregistered consumer: {}", consumer.address())
                        unregistered.set(true)
                        client.post(5445, platformHost, "/graphql")
                            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
                            .sendJsonObject(
                                JsonObject().put(
                                    "query",
                                    """mutation clearLiveInstruments {
    clearLiveInstruments
}"""
                                )
                            ).onComplete { it2: AsyncResult<HttpResponse<Buffer?>?> ->
                                if (it2.succeeded()) {
                                    log.info("Cleared live instruments")
                                    testContext.completeNow()
                                } else {
                                    testContext.failNow(it2.cause())
                                }
                            }
                    } else {
                        testContext.failNow(it3.cause())
                    }
                }
            }.completionHandler {
                if (it.succeeded()) {
                    log.info("Registered consumer: {}", consumer.address())
                    client.post(5445, platformHost, "/graphql")
                        .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
                        .sendJsonObject(
                            JsonObject().put(
                                "query",
                                """mutation addLiveLog(${"$"}input: LiveLogInput!) {
    addLiveLog(input: ${"$"}input) {
        id
        logFormat
        logArguments
        location {
            source
            line
        }
        condition
        expiresAt
        hitLimit
    }
}"""
                            ).put(
                                "variables", JsonObject()
                                    .put(
                                        "input", JsonObject()
                                            .put("condition", "1==2")
                                            .put("logFormat", "test")
                                            .put(
                                                "location", JsonObject()
                                                    .put("source", "spp.example.webapp.edge.SingleThread")
                                                    .put("line", 37)
                                            )
                                    )
                            )
                        ).onComplete { it2: AsyncResult<HttpResponse<Buffer?>?> ->
                            if (it2.succeeded()) {
                                log.info("Added live log. Reconnecting to platform")
                                connectToPlatform()
                            } else {
                                testContext.failNow(it2.cause())
                            }
                        }
                } else {
                    testContext.failNow(it.cause())
                }
            }
        }
        log.info("Disconnecting from platform")
        disconnectFromPlatform()
        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw RuntimeException(testContext.causeOfFailure())
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProbeIntegrationTest::class.java)
        const val SYSTEM_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJzeXN0ZW0iLCJjcmVhdGVkX2F0IjoxNjIyNDIxMzY0ODY4" +
                    "LCJleHBpcmVzX2F0IjoxNjUzOTU3MzY0ODY4LCJpYXQiOjE2MjI0MjEzNjR9.ZVHtxQkfCF7KM_dyDOgawbwpEAsmnCWB4c8I" +
                    "52svPvVc-SlzkEe0SYrNufNPniYZeM3IF0Gbojl_DSk2KleAz9CLRO3zfegciXKeEEvGjsNOqfQjgU5yZtBWmTimVXq5QoZME" +
                    "GuAojACaf-m4J0H7o4LQNGwrDVA-noXVE0Eu84A5HxkjrRuFlQWv3fzqSRC_-lI0zRKuFGD-JkIfJ9b_wP_OjBWT6nmqkZn_J" +
                    "mK7UwniTUJjocszSA2Ma3XLx2xVPzBcz00QWyjhIyiftxNQzgqLl1XDVkRtzXUIrHnFCR8BcgR_PsqTBn5nH7aCp16zgmkkbO" +
                    "pmJXlNpDSVz9zUY4NOrB1jTzDB190COrfCXddb7JO6fmpet9_Zd3kInJx4XsT3x7JfBSWr9FBqFoUmNkgIWjkbN1TpwMyizXA" +
                    "Sp1nOmwJ64FDIbSpfpgUAqfSWXKZYhSisfnBLEyHCjMSPzVmDh949w-W1wU9q5nGFtrx6PTOxK_WKOiWU8_oeTjL0pD8pKXqJ" +
                    "MaLW-OIzfrl3kzQNuF80YT-nxmNtp5PrcxehprlPmqSB_dyTHccsO3l63d8y9hiIzfRUgUjTJbktFn5t41ADARMs_0WMpIGZJ" +
                    "yxcVssstt4J1Gj8WUFOdqPsIKigJZMn3yshC5S-KY-7S0dVd0VXgvpPqmpb9Q9Uho"

        @BeforeAll
        @JvmStatic
        fun setup() {
            val testContext = VertxTestContext()
            val platformHost = if (System.getenv("SPP_PLATFORM_HOST") != null)
                System.getenv("SPP_PLATFORM_HOST") else "localhost"
            setString("platform_host", platformHost)
            val client = WebClient.create(
                vertx, WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
            )

            //wait for remotes to register
            vertx!!.setPeriodic(5000) { id: Long ->
                log.info("Checking for remotes")
                client[5445, platformHost, "/clients"]
                    .bearerTokenAuthentication(SYSTEM_JWT_TOKEN).send()
                    .onComplete {
                        if (it.succeeded()) {
                            val probes = it.result().bodyAsJsonObject().getJsonArray("probes")
                            for (i in 0 until probes.size()) {
                                val probe = probes.getJsonObject(i)
                                if (probe.getString("probeId") == PROBE_ID) {
                                    if (probe.getJsonArray("remotes").size() == 4) {
                                        log.info("Probe is ready")
                                        vertx!!.cancelTimer(id)
                                        client.close()
                                        testContext.completeNow()
                                    }
                                }
                            }
                        } else {
                            testContext.failNow(it.cause())
                        }
                    }
            }
            if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
                if (testContext.failed()) {
                    throw RuntimeException(testContext.causeOfFailure())
                }
            } else {
                throw RuntimeException("Test timed out")
            }
        }
    }
}