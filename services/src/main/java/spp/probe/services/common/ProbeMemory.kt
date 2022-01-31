/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
