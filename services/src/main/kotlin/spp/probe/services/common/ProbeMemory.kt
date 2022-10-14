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

    fun <T> computeIfAbsent(key: String, function: Function<String, T?>?): T? {
        return memory.computeIfAbsent(key, function!!) as T?
    }

    operator fun get(key: String): Any? {
        return memory[key]
    }

    fun put(key: String, value: Any?) {
        memory[key] = value
    }

    fun remove(key: String): Any? {
        return memory.remove(key)
    }

    fun clear() {
        memory.clear()
    }
}
