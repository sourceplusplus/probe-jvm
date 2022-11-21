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
package spp.probe.services.common

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

object ProbeMemory {

    private val memory: MutableMap<String, Any?> = ConcurrentHashMap()
    private val localMemory: ThreadLocal<MutableMap<String, Any?>> = ThreadLocal.withInitial { HashMap() }

    fun <T> computeGlobal(key: String, function: Function<String, T?>): T {
        return memory.computeIfAbsent(key, function) as T
    }

    fun putLocal(key: String, value: Any?) {
        localMemory.get()[key] = value
    }

    fun removeLocal(key: String): Any? {
        return localMemory.get().remove(key)
    }

    fun putContextVariable(instrumentId: String, key: String, value: Pair<String, Any?>) {
        val contextVariableMap = localMemory.get().computeIfAbsent("contextVariables:$instrumentId") {
            HashMap<String, Pair<String, Any?>>()
        } as HashMap<String, Pair<String, Any?>>
        contextVariableMap[key] = value
    }

    fun getContextVariables(instrumentId: String, removeData: Boolean = true): Map<String, Pair<String, Any?>> {
        if (!removeData) {
            return localMemory.get().getOrDefault(
                "contextVariables:$instrumentId",
                emptyMap<String, Pair<String, Any?>>()
            ) as Map<String, Pair<String, Any?>>
        }

        return localMemory.get().remove(
            "contextVariables:$instrumentId"
        ) as Map<String, Pair<String, Any?>>? ?: HashMap()
    }

    fun putLocalVariable(instrumentId: String, key: String, value: Pair<String, Any?>) {
        val localVariableMap = localMemory.get().computeIfAbsent("localVariables:$instrumentId") {
            HashMap<String, Pair<String, Any?>>()
        } as HashMap<String, Pair<String, Any?>>
        localVariableMap[key] = value
    }

    fun getLocalVariables(instrumentId: String, removeData: Boolean = true): Map<String, Pair<String, Any?>> {
        if (!removeData) {
            return localMemory.get().getOrDefault(
                "localVariables:$instrumentId",
                emptyMap<String, Pair<String, Any?>>()
            ) as Map<String, Pair<String, Any?>>
        }

        return localMemory.get().remove(
            "localVariables:$instrumentId"
        ) as Map<String, Pair<String, Any?>>? ?: HashMap()
    }

    fun putFieldVariable(instrumentId: String, key: String, value: Pair<String, Any?>) {
        val fieldVariableMap = localMemory.get().computeIfAbsent("fieldVariables:$instrumentId") {
            HashMap<String, Pair<String, Any?>>()
        } as HashMap<String, Pair<String, Any?>>
        fieldVariableMap[key] = value
    }

    fun getFieldVariables(instrumentId: String, removeData: Boolean = true): Map<String, Pair<String, Any?>> {
        if (!removeData) {
            return localMemory.get().getOrDefault(
                "fieldVariables:$instrumentId",
                emptyMap<String, Pair<String, Any?>>()
            ) as Map<String, Pair<String, Any?>>
        }

        return localMemory.get().remove(
            "fieldVariables:$instrumentId"
        ) as Map<String, Pair<String, Any?>>? ?: HashMap()
    }

    fun putStaticVariable(instrumentId: String, key: String, value: Pair<String, Any?>) {
        val staticVariableMap = localMemory.get().computeIfAbsent("staticVariables:$instrumentId") {
            HashMap<String, Pair<String, Any?>>()
        } as HashMap<String, Pair<String, Any?>>
        staticVariableMap[key] = value
    }

    fun getStaticVariables(instrumentId: String, removeData: Boolean = true): Map<String, Pair<String, Any?>> {
        if (!removeData) {
            return localMemory.get().getOrDefault(
                "staticVariables:$instrumentId",
                emptyMap<String, Pair<String, Any?>>()
            ) as Map<String, Pair<String, Any?>>
        }

        return localMemory.get().remove(
            "staticVariables:$instrumentId"
        ) as Map<String, Pair<String, Any?>>? ?: HashMap()
    }
}
