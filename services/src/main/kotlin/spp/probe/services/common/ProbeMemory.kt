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

    fun putContextVariable(instrumentId: String, key: String, value: Triple<String, Any?, Int>) {
        val contextVariableMap = localMemory.get().computeIfAbsent("contextVariables:$instrumentId") {
            HashMap<String, Triple<String, Any?, Int>>()
        } as HashMap<String, Triple<String, Any?, Int>>
        contextVariableMap[key] = value
    }

    fun getContextVariables(instrumentId: String, removeData: Boolean = true): Map<String, Triple<String, Any?, Int>> {
        if (!removeData) {
            return localMemory.get().getOrDefault(
                "contextVariables:$instrumentId",
                emptyMap<String, Triple<String, Any?, Int>>()
            ) as Map<String, Triple<String, Any?, Int>>
        }

        return localMemory.get().remove(
            "contextVariables:$instrumentId"
        ) as Map<String, Triple<String, Any?, Int>>? ?: HashMap()
    }

    fun putLocalVariable(instrumentId: String, key: String, value: Triple<String, Any?, Int>) {
        val localVariableMap = localMemory.get().computeIfAbsent("localVariables:$instrumentId") {
            HashMap<String, Triple<String, Any?, Int>>()
        } as HashMap<String, Triple<String, Any?, Int>>
        localVariableMap[key] = value
    }

    fun getLocalVariables(instrumentId: String, removeData: Boolean = true): Map<String, Triple<String, Any?, Int>> {
        if (!removeData) {
            return localMemory.get().getOrDefault(
                "localVariables:$instrumentId",
                emptyMap<String, Triple<String, Any?, Int>>()
            ) as Map<String, Triple<String, Any?, Int>>
        }

        return localMemory.get().remove(
            "localVariables:$instrumentId"
        ) as Map<String, Triple<String, Any?, Int>>? ?: HashMap()
    }

    fun putFieldVariable(instrumentId: String, key: String, value: Triple<String, Any?, Int>) {
        val fieldVariableMap = localMemory.get().computeIfAbsent("fieldVariables:$instrumentId") {
            HashMap<String, Triple<String, Any?, Int>>()
        } as HashMap<String, Triple<String, Any?, Int>>
        fieldVariableMap[key] = value
    }

    fun getFieldVariables(instrumentId: String, removeData: Boolean = true): Map<String, Triple<String, Any?, Int>> {
        if (!removeData) {
            return localMemory.get().getOrDefault(
                "fieldVariables:$instrumentId",
                emptyMap<String, Triple<String, Any?, Int>>()
            ) as Map<String, Triple<String, Any?, Int>>
        }

        return localMemory.get().remove(
            "fieldVariables:$instrumentId"
        ) as Map<String, Triple<String, Any?, Int>>? ?: HashMap()
    }

    fun putStaticVariable(instrumentId: String, key: String, value: Triple<String, Any?, Int>) {
        val staticVariableMap = localMemory.get().computeIfAbsent("staticVariables:$instrumentId") {
            HashMap<String, Triple<String, Any?, Int>>()
        } as HashMap<String, Triple<String, Any?, Int>>
        staticVariableMap[key] = value
    }

    fun getStaticVariables(instrumentId: String, removeData: Boolean = true): Map<String, Triple<String, Any?, Int>> {
        if (!removeData) {
            return localMemory.get().getOrDefault(
                "staticVariables:$instrumentId",
                emptyMap<String, Triple<String, Any?, Int>>()
            ) as Map<String, Triple<String, Any?, Int>>
        }

        return localMemory.get().remove(
            "staticVariables:$instrumentId"
        ) as Map<String, Triple<String, Any?, Int>>? ?: HashMap()
    }
}
