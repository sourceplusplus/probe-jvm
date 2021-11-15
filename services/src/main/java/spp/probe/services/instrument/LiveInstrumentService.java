package spp.probe.services.instrument;

import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.pool.TypePool;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import spp.probe.services.common.ContextMap;
import spp.probe.services.common.ContextReceiver;
import spp.probe.services.common.ModelSerializer;
import spp.probe.services.common.model.ActiveLiveInstrument;
import spp.probe.services.common.transform.LiveTransformer;
import spp.protocol.instrument.LiveInstrument;
import spp.protocol.instrument.LiveSourceLocation;
import spp.protocol.instrument.breakpoint.LiveBreakpoint;
import spp.protocol.instrument.log.LiveLog;
import spp.protocol.instrument.meter.LiveMeter;
import spp.protocol.probe.error.LiveInstrumentException;
import spp.protocol.probe.error.LiveInstrumentException.ErrorType;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static spp.protocol.platform.PlatformAddress.*;

public class LiveInstrumentService {

    private static final Map<String, ActiveLiveInstrument> instruments = new ConcurrentHashMap<>();
    private static final Map<String, ActiveLiveInstrument> applyingInstruments = new ConcurrentHashMap<>();
    private final static SpelExpressionParser parser = new SpelExpressionParser(
            new SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveInstrumentService.class.getClassLoader()));
    private static BiConsumer<String, String> instrumentEventConsumer;
    private static Map<ClassLoader, TypePool> poolMap = new HashMap<>();
    private static final Timer timer = new Timer("LiveInstrumentScheduler", true);
    private static Instrumentation instrumentation;

    static {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                List<ActiveLiveInstrument> removeInstruments = new ArrayList<>();
                instruments.values().forEach(it -> {
                    if (it.getInstrument().getExpiresAt() != null
                            && System.currentTimeMillis() >= it.getInstrument().getExpiresAt()) {
                        removeInstruments.add(it);
                    }
                });
                applyingInstruments.values().forEach(it -> {
                    if (it.getInstrument().getExpiresAt() != null
                            && System.currentTimeMillis() >= it.getInstrument().getExpiresAt()) {
                        removeInstruments.add(it);
                    }
                });
                removeInstruments.forEach(it -> _removeInstrument(it.getInstrument(), null));
            }
        }, 5000, 5000);
    }

    public static LiveInstrumentApplier liveInstrumentApplier = (inst, instrument) -> {
        Class clazz = null;
        for (ClassLoader classLoader : poolMap.keySet()) {
            try {
                clazz = Class.forName(instrument.getInstrument().getLocation().getSource(), true, classLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (poolMap.isEmpty()) {
            try {
                clazz = Class.forName(instrument.getInstrument().getLocation().getSource());
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (clazz == null) {
            if (instrument.getInstrument().getApplyImmediately()) {
                throw new LiveInstrumentException(ErrorType.CLASS_NOT_FOUND, instrument.getInstrument().getLocation().getSource()
                ).toEventBusException();
            } else if (!instrument.isRemoval()) {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        liveInstrumentApplier.apply(inst, instrument);
                    }
                }, 5000);
            }
            return;
        }

        ClassFileTransformer transformer = new LiveTransformer(instrument.getInstrument().getLocation().getSource());
        try {
            if (!instrument.isRemoval()) {
                applyingInstruments.put(instrument.getInstrument().getId(), instrument);
            }
            inst.addTransformer(transformer, true);
            inst.retransformClasses(clazz);
            instrument.setLive(true);
            if (!instrument.isRemoval()) {
                if (instrument.getInstrument() instanceof LiveLog) {
                    instrumentEventConsumer.accept(LIVE_LOG_APPLIED.getAddress(), ModelSerializer.INSTANCE.toJson(instrument));
                } else if (instrument.getInstrument() instanceof LiveBreakpoint) {
                    instrumentEventConsumer.accept(LIVE_BREAKPOINT_APPLIED.getAddress(), ModelSerializer.INSTANCE.toJson(instrument));
                } else if (instrument.getInstrument() instanceof LiveMeter) {
                    instrumentEventConsumer.accept(LIVE_METER_APPLIED.getAddress(), ModelSerializer.INSTANCE.toJson(instrument));
                }
//                else if (instrument.getInstrument() instanceof LiveSpan) {
//                    instrumentEventConsumer.accept(LIVE_SPAN_APPLIED.getAddress(), ModelSerializer.INSTANCE.toJson(instrument));
//                }
            }
        } catch (Throwable ex) {
            //remove and re-transform
            _removeInstrument(instrument.getInstrument(), ex);

            applyingInstruments.remove(instrument.getInstrument().getId());
            inst.addTransformer(transformer, true);
            try {
                inst.retransformClasses(clazz);
            } catch (UnmodifiableClassException e) {
                throw new RuntimeException(e);
            }
        } finally {
            applyingInstruments.remove(instrument.getInstrument().getId());
            inst.removeTransformer(transformer);
        }
    };

    private LiveInstrumentService() {
    }

    @SuppressWarnings("unused")
    public static void setPoolMap(Map poolMap) {
        LiveInstrumentService.poolMap = poolMap;
    }

    @SuppressWarnings("unused")
    public static void setInstrumentEventConsumer(BiConsumer instrumentEventConsumer) {
        LiveInstrumentService.instrumentEventConsumer = instrumentEventConsumer;
    }

    public static void setInstrumentApplier(LiveInstrumentApplier liveInstrumentApplier) {
        LiveInstrumentService.liveInstrumentApplier = liveInstrumentApplier;
    }

    public static void setInstrumentation(Instrumentation instrumentation) {
        LiveInstrumentService.instrumentation = instrumentation;
    }

    public static Map<String, ActiveLiveInstrument> getInstrumentsMap() {
        return new HashMap<>(instruments);
    }

    public static void clearAll() {
        instruments.clear();
        applyingInstruments.clear();
    }

    @SuppressWarnings("unused")
    public static String applyInstrument(LiveInstrument liveInstrument) {
        ActiveLiveInstrument existingBreakpoint = applyingInstruments.get(liveInstrument.getId());
        if (existingBreakpoint == null) existingBreakpoint = instruments.get(liveInstrument.getId());
        if (existingBreakpoint != null) {
            return ModelSerializer.INSTANCE.toJson(existingBreakpoint.getInstrument());
        } else {
            ActiveLiveInstrument activeInstrument;
            if (liveInstrument.getCondition() != null && !liveInstrument.getCondition().isEmpty()) {
                try {
                    Expression expression = parser.parseExpression(liveInstrument.getCondition());
                    activeInstrument = new ActiveLiveInstrument(liveInstrument, expression);
                } catch (ParseException ex) {
                    throw new LiveInstrumentException(ErrorType.CONDITIONAL_FAILED, ex.getMessage())
                            .toEventBusException();
                }
            } else {
                activeInstrument = new ActiveLiveInstrument(liveInstrument);
            }

            liveInstrumentApplier.apply(instrumentation, activeInstrument);
            instruments.put(liveInstrument.getId(), activeInstrument);
            return ModelSerializer.INSTANCE.toJson(activeInstrument.getInstrument());
        }
    }

    @SuppressWarnings("unused")
    public static Collection<String> removeInstrument(String source, int line, String instrumentId) {
        if (instrumentId != null) {
            ActiveLiveInstrument removedInstrument = instruments.remove(instrumentId);
            if (removedInstrument != null) {
                removedInstrument.setRemoval(true);
                if (removedInstrument.isLive()) {
                    liveInstrumentApplier.apply(instrumentation, removedInstrument);
                    return Collections.singletonList(ModelSerializer.INSTANCE.toJson(removedInstrument.getInstrument()));
                }
            }
        } else {
            List<String> removedInstruments = new ArrayList<>();
            getInstruments(new LiveSourceLocation(source, line)).forEach(it -> {
                ActiveLiveInstrument removedInstrument = instruments.remove(it.getInstrument().getId());

                if (removedInstrument != null) {
                    removedInstrument.setRemoval(true);
                    if (removedInstrument.isLive()) {
                        liveInstrumentApplier.apply(instrumentation, removedInstrument);
                        removedInstruments.add(ModelSerializer.INSTANCE.toJson(removedInstrument.getInstrument()));
                    }
                }
            });
            return removedInstruments;
        }
        return Collections.EMPTY_LIST;
    }

    public static void _removeInstrument(LiveInstrument instrument, Throwable ex) {
        removeInstrument(instrument.getLocation().getSource(), instrument.getLocation().getLine(), instrument.getId());

        Map<String, Object> map = new HashMap<>();
        if (instrument instanceof LiveBreakpoint) {
            map.put("breakpoint", ModelSerializer.INSTANCE.toJson(instrument));
        } else if (instrument instanceof LiveLog) {
            map.put("log", ModelSerializer.INSTANCE.toJson(instrument));
        } else if (instrument instanceof LiveMeter) {
            map.put("meter", ModelSerializer.INSTANCE.toJson(instrument));
        }
//        else if (instrument instanceof LiveSpan) {
//            map.put("span", ModelSerializer.INSTANCE.toJson(instrument));
//        }
        else {
            throw new IllegalArgumentException(instrument.getClass().getSimpleName());
        }
        map.put("occurredAt", System.currentTimeMillis());
        if (ex != null) {
            map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));
        }

        if (instrument instanceof LiveBreakpoint) {
            instrumentEventConsumer.accept(LIVE_BREAKPOINT_REMOVED.getAddress(), ModelSerializer.INSTANCE.toJson(map));
        } else if (instrument instanceof LiveLog) {
            instrumentEventConsumer.accept(LIVE_LOG_REMOVED.getAddress(), ModelSerializer.INSTANCE.toJson(map));
        } else if (instrument instanceof LiveMeter) {
            instrumentEventConsumer.accept(LIVE_METER_REMOVED.getAddress(), ModelSerializer.INSTANCE.toJson(map));
        } else {
            instrumentEventConsumer.accept(LIVE_SPAN_REMOVED.getAddress(), ModelSerializer.INSTANCE.toJson(map));
        }
    }

    public static List<ActiveLiveInstrument> getInstruments(LiveSourceLocation location) {
        Set<ActiveLiveInstrument> instruments = LiveInstrumentService.instruments.values().stream()
                .filter(it -> it.getInstrument().getLocation().equals(location))
                .collect(Collectors.toSet());
        instruments.addAll(applyingInstruments.values().stream()
                .filter(it -> it.getInstrument().getLocation().equals(location))
                .collect(Collectors.toSet()));
        return new ArrayList<>(instruments);
    }

    @SuppressWarnings("unused")
    public static boolean isInstrumentEnabled(String instrumentId) {
        boolean applied = instruments.containsKey(instrumentId);
        if (applied) {
            return true;
        } else {
            return applyingInstruments.containsKey(instrumentId);
        }
    }

    @SuppressWarnings("unused")
    public static boolean isHit(String instrumentId) {
        ActiveLiveInstrument instrument = instruments.get(instrumentId);
        if (instrument == null) {
            return false;
        }

        if (instrument.getThrottle().isRateLimited()) {
            ContextReceiver.clear(instrumentId);
            return false;
        }

        if (instrument.getExpression() == null) {
            if (instrument.isFinished()) {
                _removeInstrument(instrument.getInstrument(), null);
            }
            return true;
        }

        try {
            if (evaluateCondition(instrument)) {
                if (instrument.isFinished()) {
                    _removeInstrument(instrument.getInstrument(), null);
                }
                return true;
            } else {
                ContextReceiver.clear(instrumentId);
                return false;
            }
        } catch (Throwable e) {
            ContextReceiver.clear(instrumentId);
            _removeInstrument(instrument.getInstrument(), e);
            return false;
        }
    }

    private static boolean evaluateCondition(ActiveLiveInstrument liveInstrument) {
        ContextMap rootObject = ContextReceiver.get(liveInstrument.getInstrument().getId());
        StandardEvaluationContext context = new StandardEvaluationContext(rootObject);
        return liveInstrument.getExpression().getValue(context, Boolean.class);
    }
}
