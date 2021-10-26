package integration;

import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spp.probe.ProbeConfiguration;
import spp.probe.SourceProbe;
import spp.protocol.probe.command.LiveInstrumentCommand;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static spp.probe.SourceProbe.vertx;
import static spp.protocol.probe.ProbeAddress.LIVE_LOG_REMOTE;
import static spp.protocol.probe.command.LiveInstrumentCommand.CommandType.ADD_LIVE_INSTRUMENT;

@ExtendWith(VertxExtension.class)
public class IntegrationTest {

    private final static Logger log = LoggerFactory.getLogger(IntegrationTest.class);

    @Test
    public void verifyClientConnected() throws Exception {
        VertxTestContext testContext = new VertxTestContext();
        assertTrue(SourceProbe.isAgentInitialized());
        ProbeConfiguration.setQuietMode(false);

        String platformHost = (System.getenv("SPP_PLATFORM_HOST") != null)
                ? System.getenv("SPP_PLATFORM_HOST") : "localhost";
        ProbeConfiguration.setString("platform_host", platformHost);

        WebClient client = WebClient.create(vertx);
        client.get(5445, platformHost, "/clients")
                .send().onComplete(it -> {
                    if (it.succeeded()) {
                        var result = it.result().bodyAsJsonObject();
                        var processors = result.getJsonArray("processors");
                        var markers = result.getJsonArray("markers");
                        var probes = result.getJsonArray("probes");
                        testContext.verify(() -> {
                            assertNotEquals(0, processors.size());
                            assertEquals(0, markers.size());
                            assertNotEquals(0, probes.size());
                        });

                        client.close();
                        testContext.completeNow();
                    } else {
                        testContext.failNow(it.cause());
                    }
                });

        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw new RuntimeException(testContext.causeOfFailure());
            }
        } else {
            throw new RuntimeException("Test timed out");
        }
    }

    @Test
    public void receivePendingInstrumentsOnReconnect() throws Exception {
        VertxTestContext testContext = new VertxTestContext();
        assertTrue(SourceProbe.isAgentInitialized());
        ProbeConfiguration.setQuietMode(false);

        SourceProbe.tcpSocket.closeHandler(event -> {
            log.info("Disconnected from platform");

            String platformHost = (System.getenv("SPP_PLATFORM_HOST") != null)
                    ? System.getenv("SPP_PLATFORM_HOST") : "localhost";
            ProbeConfiguration.setString("platform_host", platformHost);

            WebClient client = WebClient.create(vertx);
            AtomicBoolean unregistered = new AtomicBoolean(false);
            MessageConsumer<JsonObject> consumer = vertx.eventBus().localConsumer("local." + LIVE_LOG_REMOTE.getAddress());
            consumer.handler(it -> {
                log.info("Got command: {}", it.body());
                if (unregistered.get()) {
                    log.warn("Ignoring message after unregistered...");
                    return;
                }

                LiveInstrumentCommand command = Json.decodeValue(it.body().toString(), LiveInstrumentCommand.class);

                testContext.verify(() -> {
                    assertEquals(ADD_LIVE_INSTRUMENT, command.getCommandType());
                    assertEquals(1, command.getContext().getLiveInstruments().size());


                    JsonObject liveLog = new JsonObject(command.getContext().getLiveInstruments().get(0));
                    assertEquals("test", liveLog.getString("logFormat"));
                });

                consumer.unregister(it3 -> {
                    if (it3.succeeded()) {
                        log.info("Unregistered consumer: {}", consumer.address());
                        unregistered.set(true);
                        client.post(5445, platformHost, "/graphql")
                                .sendJsonObject(
                                        new JsonObject().put(
                                                "query",
                                                "mutation clearLiveInstruments {\n" +
                                                        "    clearLiveInstruments\n" +
                                                        "}"
                                        )
                                ).onComplete(it2 -> {
                                    if (it2.succeeded()) {
                                        log.info("Cleared live instruments");
                                        testContext.completeNow();
                                    } else {
                                        testContext.failNow(it2.cause());
                                    }
                                });
                    } else {
                        testContext.failNow(it3.cause());
                    }
                });
            }).completionHandler(it -> {
                if (it.succeeded()) {
                    log.info("Registered consumer: {}", consumer.address());
                    client.post(5445, platformHost, "/graphql")
                            .sendJsonObject(
                                    new JsonObject().put(
                                            "query",
                                            "mutation addLiveLog($input: LiveLogInput!) {\n" +
                                                    "    addLiveLog(input: $input) {\n" +
                                                    "        id\n" +
                                                    "        logFormat\n" +
                                                    "        logArguments\n" +
                                                    "        location {\n" +
                                                    "            source\n" +
                                                    "            line\n" +
                                                    "        }\n" +
                                                    "        condition\n" +
                                                    "        expiresAt\n" +
                                                    "        hitLimit\n" +
                                                    "    }\n" +
                                                    "}"
                                    ).put("variables", new JsonObject()
                                            .put("input", new JsonObject()
                                                    .put("condition", "1==2")
                                                    .put("logFormat", "test")
                                                    .put("location", new JsonObject()
                                                            .put("source", "spp.example.webapp.edge.SingleThread")
                                                            .put("line", 37))
                                            ))
                            ).onComplete(it2 -> {
                                if (it2.succeeded()) {
                                    log.info("Reconnecting to platform");
                                    SourceProbe.connectToPlatform();
                                } else {
                                    testContext.failNow(it2.cause());
                                }
                            });
                } else {
                    testContext.failNow(it.cause());
                }
            });
        });

        log.info("Disconnecting from platform");
        SourceProbe.disconnectFromPlatform();

        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw new RuntimeException(testContext.causeOfFailure());
            }
        } else {
            throw new RuntimeException("Test timed out");
        }
    }
}
