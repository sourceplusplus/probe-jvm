package spp.probe.services.common

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

object ProbeMemory {

    private val memory: MutableMap<String, Any?> = ConcurrentHashMap()

    fun <T> computeIfAbsent(key: String, function: Function<String, T?>?): T? {
        return memory.computeIfAbsent(key, function!!) as T?
    }

    operator fun get(key: String): Any? {
        return memory[key]
    }

    fun put(key: String, value: Any?) {
        memory[key] = value
    }

    fun remove(key: String) {
        memory.remove(key)
    }

    fun clear() {
        memory.clear()
    }
}
