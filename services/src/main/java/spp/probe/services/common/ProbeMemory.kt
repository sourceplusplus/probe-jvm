package spp.probe.services.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ProbeMemory {

    private static final Map<String, Object> memory = new ConcurrentHashMap<>();

    public static <T> T computeIfAbsent(String key, Function<String, T> function) {
        return (T) memory.computeIfAbsent(key, function);
    }

    public static Object get(String key) {
        return memory.get(key);
    }

    public static void put(String key, Object value) {
        memory.put(key, value);
    }

    public static void remove(String key) {
        memory.remove(key);
    }

    public static void clear() {
        memory.clear();
    }
}
