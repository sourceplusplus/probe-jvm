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
package spp.probe.services.common.serialize

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.internal.bind.JsogRegistry
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import spp.probe.services.common.ModelSerializer
import java.io.IOException
import java.lang.instrument.Instrumentation

class DepthSizeCappedTypeAdapterFactory(val maxDepth: Int) : TypeAdapterFactory {

    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        return if (instrumentation == null || maxMemorySize == -1L) null else object : TypeAdapter<T>() {

            @Throws(IOException::class)
            override fun write(jsonWriter: JsonWriter, value: T?) {
                if (value == null) {
                    jsonWriter.nullValue()
                    return
                } else if (value is Class<*>) {
                    jsonWriter.value(value.name)
                    return
                }

                JsogRegistry.get().userData.putIfAbsent("depth", 0)
                if ((JsogRegistry.get().userData["depth"] as Int) >= maxDepth) {
                    jsonWriter.beginObject()
                    jsonWriter.name("@skip")
                    jsonWriter.value("MAX_DEPTH_EXCEEDED")
                    jsonWriter.name("@class")
                    jsonWriter.value(value.javaClass.name)
                    jsonWriter.name("@id")
                    jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
                    jsonWriter.endObject()
                    return
                }

                val objSize = instrumentation!!.getObjectSize(value)
                if (objSize <= maxMemorySize) {
                    JsogRegistry.get().userData["depth"] = (JsogRegistry.get().userData["depth"] as Int) + 1
                    try {
                        ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(
                            this@DepthSizeCappedTypeAdapterFactory, type
                        ).write(jsonWriter, value)
                    } catch (e: Exception) {
                        jsonWriter.beginObject()
                        jsonWriter.name("@skip")
                        jsonWriter.value("EXCEPTION_OCCURRED")
                        jsonWriter.name("@class")
                        jsonWriter.value(value.javaClass.name)
                        jsonWriter.name("@size")
                        jsonWriter.value(objSize.toString())
                        jsonWriter.name("@cause")
                        jsonWriter.value(e.message)
                        jsonWriter.endObject()
                    }
                    JsogRegistry.get().userData["depth"] = (JsogRegistry.get().userData["depth"] as Int) - 1
                } else {
                    jsonWriter.beginObject()
                    jsonWriter.name("@skip")
                    jsonWriter.value("MAX_SIZE_EXCEEDED")
                    jsonWriter.name("@class")
                    jsonWriter.value(value.javaClass.name)
                    jsonWriter.name("@size")
                    jsonWriter.value(objSize.toString())
                    jsonWriter.name("@id")
                    jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
                    jsonWriter.endObject()
                }
            }

            override fun read(jsonReader: JsonReader): T? = null
        }
    }

    companion object {
        private var instrumentation: Instrumentation? = null
        private var maxMemorySize: Long = -1

        @JvmStatic
        fun setInstrumentation(instrumentation: Instrumentation?) {
            Companion.instrumentation = instrumentation
        }

        @JvmStatic
        fun setMaxMemorySize(maxMemorySize: Long) {
            Companion.maxMemorySize = maxMemorySize
        }
    }
}
