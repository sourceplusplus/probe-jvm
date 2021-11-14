package spp.probe.control;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper;
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer;
import org.apache.skywalking.apm.agent.core.plugin.WitnessFinder;
import spp.probe.SourceProbe;
import spp.protocol.instrument.LiveInstrument;
import spp.protocol.instrument.breakpoint.LiveBreakpoint;
import spp.protocol.instrument.log.LiveLog;
import spp.protocol.instrument.meter.LiveMeter;
import spp.protocol.platform.PlatformAddress;
import spp.protocol.probe.command.LiveInstrumentCommand;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static spp.probe.SourceProbe.PROBE_ID;
import static spp.probe.SourceProbe.instrumentation;
import static spp.protocol.probe.ProbeAddress.*;

public class LiveInstrumentRemote extends AbstractVerticle {

    private static final BiConsumer<String, String> EVENT_CONSUMER = (address, json) -> FrameHelper.sendFrame(
            BridgeEventType.PUBLISH.name().toLowerCase(), address, new JsonObject(json), SourceProbe.tcpSocket
    );

    private Method applyInstrument;
    private Method removeInstrument;
    private static Method putBreakpoint;
    private static Method putLog;
    private static Method putMeter;
    private static Method isInstrumentEnabled;
    private static Method putLocalVariable;
    private static Method putField;
    private static Method putStaticField;
    private static Method isHit;

    @Override
    public void start() {
        try {
            ClassLoader agentClassLoader = (ClassLoader) Class.forName("org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader")
                    .getMethod("getDefault").invoke(null);
            Class serviceClass = Class.forName(
                    "spp.probe.services.instrument.LiveInstrumentService", false, agentClassLoader);
            Field poolMapField = WitnessFinder.class.getDeclaredField("poolMap");
            poolMapField.setAccessible(true);
            serviceClass.getMethod("setPoolMap", Map.class).invoke(null, poolMapField.get(WitnessFinder.INSTANCE));
            serviceClass.getMethod("setInstrumentEventConsumer", BiConsumer.class).invoke(null, EVENT_CONSUMER);
            serviceClass.getMethod("setInstrumentation", Instrumentation.class).invoke(null, instrumentation);

            applyInstrument = serviceClass.getMethod("applyInstrument", LiveInstrument.class);
            removeInstrument = serviceClass.getMethod("removeInstrument",
                    String.class, int.class, String.class);
            isInstrumentEnabled = serviceClass.getMethod("isInstrumentEnabled", String.class);
            isHit = serviceClass.getMethod("isHit", String.class);

            Class contextClass = Class.forName(
                    "spp.probe.services.common.ContextReceiver", false, agentClassLoader);
            putLocalVariable = contextClass.getMethod("putLocalVariable",
                    String.class, String.class, Object.class);
            putField = contextClass.getMethod("putField",
                    String.class, String.class, Object.class);
            putStaticField = contextClass.getMethod("putStaticField",
                    String.class, String.class, Object.class);
            putBreakpoint = contextClass.getMethod("putBreakpoint",
                    String.class, String.class, int.class, Throwable.class);
            putLog = contextClass.getMethod("putLog",
                    String.class, String.class, String[].class);
            putMeter = contextClass.getMethod("putMeter", String.class);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        vertx.eventBus().<JsonObject>localConsumer("local." + LIVE_BREAKPOINT_REMOTE.getAddress() + ":" + PROBE_ID).handler(it -> {
            try {
                LiveInstrumentCommand command = Json.decodeValue(it.body().toString(), LiveInstrumentCommand.class);
                switch (command.getCommandType()) {
                    case ADD_LIVE_INSTRUMENT:
                        addBreakpoint(command);
                        break;
                    case REMOVE_LIVE_INSTRUMENT:
                        removeInstrument(command);
                        break;
                }
            } catch (InvocationTargetException ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("occurredAt", System.currentTimeMillis());
                if (ex.getCause() != null) {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getCause(), 4000));
                } else {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getTargetException(), 4000));
                }

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_BREAKPOINT_REMOVED.getAddress(),
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            } catch (Throwable ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("occurredAt", System.currentTimeMillis());
                map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_BREAKPOINT_REMOVED.getAddress(),
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            }
        });
        vertx.eventBus().<JsonObject>localConsumer("local." + LIVE_LOG_REMOTE.getAddress() + ":" + PROBE_ID).handler(it -> {
            try {
                LiveInstrumentCommand command = Json.decodeValue(it.body().toString(), LiveInstrumentCommand.class);
                switch (command.getCommandType()) {
                    case ADD_LIVE_INSTRUMENT:
                        addLog(command);
                        break;
                    case REMOVE_LIVE_INSTRUMENT:
                        removeInstrument(command);
                        break;
                }
            } catch (InvocationTargetException ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("occurredAt", System.currentTimeMillis());
                if (ex.getCause() != null) {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getCause(), 4000));
                } else {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getTargetException(), 4000));
                }

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_LOG_REMOVED.getAddress(),
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            } catch (Throwable ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("occurredAt", System.currentTimeMillis());
                map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_LOG_REMOVED.getAddress(),
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            }
        });
        vertx.eventBus().<JsonObject>localConsumer("local." + LIVE_METER_REMOTE.getAddress() + ":" + PROBE_ID).handler(it -> {
            try {
                LiveInstrumentCommand command = Json.decodeValue(it.body().toString(), LiveInstrumentCommand.class);
                switch (command.getCommandType()) {
                    case ADD_LIVE_INSTRUMENT:
                        addMeter(command);
                        break;
                    case REMOVE_LIVE_INSTRUMENT:
                        removeInstrument(command);
                        break;
                }
            } catch (InvocationTargetException ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("occurredAt", System.currentTimeMillis());
                if (ex.getCause() != null) {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getCause(), 4000));
                } else {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getTargetException(), 4000));
                }

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_METER_REMOVED.getAddress(),
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            } catch (Throwable ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("occurredAt", System.currentTimeMillis());
                map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_METER_REMOVED.getAddress(),
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            }
        });
    }

    private void addBreakpoint(LiveInstrumentCommand command) throws Exception {
        String breakpointData = command.getContext().getLiveInstruments().get(0);
        applyInstrument.invoke(null, Json.decodeValue(breakpointData, LiveBreakpoint.class));
    }

    private void addLog(LiveInstrumentCommand command) throws Exception {
        String logData = command.getContext().getLiveInstruments().get(0);
        applyInstrument.invoke(null, Json.decodeValue(logData, LiveLog.class));
    }

    private void addMeter(LiveInstrumentCommand command) throws Exception {
        String meterData = command.getContext().getLiveInstruments().get(0);
        applyInstrument.invoke(null, Json.decodeValue(meterData, LiveMeter.class));
    }

    private void removeInstrument(LiveInstrumentCommand command) throws Exception {
        for (String breakpointData : command.getContext().getLiveInstruments()) {
            JsonObject breakpointObject = new JsonObject(breakpointData);
            String breakpointId = breakpointObject.getString("id");
            JsonObject location = breakpointObject.getJsonObject("location");
            String source = location.getString("source");
            int line = location.getInteger("line");
            removeInstrument.invoke(null, source, line, breakpointId);
        }
        for (String locationData : command.getContext().getLocations()) {
            JsonObject location = new JsonObject(locationData);
            String source = location.getString("source");
            int line = location.getInteger("line");
            removeInstrument.invoke(null, source, line, null);
        }
    }

    @SuppressWarnings("unused")
    public static boolean isInstrumentEnabled(String instrumentId) {
        try {
            return (Boolean) isInstrumentEnabled.invoke(null, instrumentId);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("unused")
    public static boolean isHit(String breakpointId) {
        try {
            return (Boolean) isHit.invoke(null, breakpointId);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("unused")
    public static void putBreakpoint(String breakpointId, String source, int line, Throwable ex) {
        try {
            putBreakpoint.invoke(null, breakpointId, source, line, ex);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putLog(String logId, String logFormat, String... logArguments) {
        try {
            putLog.invoke(null, logId, logFormat, logArguments);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putMeter(String meterId) {
        try {
            putMeter.invoke(null, meterId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putLocalVariable(String breakpointId, String key, Object value) {
        try {
            putLocalVariable.invoke(null, breakpointId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putField(String breakpointId, String key, Object value) {
        try {
            putField.invoke(null, breakpointId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putStaticField(String breakpointId, String key, Object value) {
        try {
            putStaticField.invoke(null, breakpointId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
