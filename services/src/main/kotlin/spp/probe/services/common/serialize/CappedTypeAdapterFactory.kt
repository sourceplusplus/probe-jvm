/*
 * Source++, the open-source live coding platform.
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

class CappedTypeAdapterFactory(val maxDepth: Int) : TypeAdapterFactory {

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

                    if (value is List<*> && value.size > maxArraySize) {
                        writeArray(jsonWriter, value.iterator(), value.size, objSize)
                    } else if (value is Map<*, *> && value.size > maxArraySize) {
                        jsonWriter.beginObject()
                        value.onEachIndexed { i, entry ->
                            if (i >= maxArraySize) return@onEachIndexed
                            println(type)
                            doWrite(jsonWriter, entry as T, entry::class.java.name, objSize)
                        }
                        jsonWriter.name("@skip")
                        jsonWriter.value("MAX_ARRAY_SIZE_EXCEEDED")
                        jsonWriter.name("@skip[size]")
                        jsonWriter.value(value.size)
                        jsonWriter.name("@skip[max]")
                        jsonWriter.value(maxArraySize)
                        jsonWriter.endObject()
                    } else if (value!!::class.java.isArray) {
                        when (value) {
                            is BooleanArray -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            is ByteArray -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            is CharArray -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            is ShortArray -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            is IntArray -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            is LongArray -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            is FloatArray -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            is DoubleArray -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            is Array<*> -> writeArray(jsonWriter, value.iterator(), value.size, objSize)
                            else -> throw IllegalArgumentException("Unsupported array type: " + value.javaClass.name)
                        }
                    } else {
                        doWrite(jsonWriter, value, value.javaClass.name, objSize)
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

            private fun writeArray(jsonWriter: JsonWriter, value: Iterator<*>, arrSize: Int, objSize: Long) {
                jsonWriter.beginArray()
                value.withIndex().forEach { (i, it) ->
                    if (i >= maxArraySize) return@forEach
                    doWrite(jsonWriter, it as T, it!!::class.java as Class<T>, objSize)
                }
                jsonWriter.beginObject()
                jsonWriter.name("@skip")
                jsonWriter.value("MAX_ARRAY_SIZE_EXCEEDED")
                jsonWriter.name("@skip[size]")
                jsonWriter.value(arrSize)
                jsonWriter.name("@skip[max]")
                jsonWriter.value(maxArraySize)
                jsonWriter.endObject()
                jsonWriter.endArray()
            }

            private fun doWrite(jsonWriter: JsonWriter, value: T?, javaClazz: Class<T>, objSize: Long) {
                try {
                    ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(
                        this@CappedTypeAdapterFactory, TypeToken.get(javaClazz)
                    ).write(jsonWriter, value)
                } catch (e: Exception) {
                    jsonWriter.beginObject()
                    jsonWriter.name("@skip")
                    jsonWriter.value("EXCEPTION_OCCURRED")
                    jsonWriter.name("@class")
                    jsonWriter.value(javaClazz.name)
                    jsonWriter.name("@size")
                    jsonWriter.value(objSize.toString())
                    jsonWriter.name("@cause")
                    jsonWriter.value(e.message)
                    jsonWriter.name("@id")
                    jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
                    jsonWriter.endObject()
                }
            }

            private fun doWrite(jsonWriter: JsonWriter, value: T?, javaClassName: String, objSize: Long) {
                try {
                    ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(
                        this@CappedTypeAdapterFactory, type
                    ).write(jsonWriter, value)
                } catch (e: Exception) {
                    jsonWriter.beginObject()
                    jsonWriter.name("@skip")
                    jsonWriter.value("EXCEPTION_OCCURRED")
                    jsonWriter.name("@class")
                    jsonWriter.value(javaClassName)
                    jsonWriter.name("@size")
                    jsonWriter.value(objSize.toString())
                    jsonWriter.name("@cause")
                    jsonWriter.value(e.message)
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
        private var maxArraySize: Int = 100

        @JvmStatic
        fun setInstrumentation(instrumentation: Instrumentation) {
            Companion.instrumentation = instrumentation
        }

        @JvmStatic
        fun setMaxMemorySize(maxMemorySize: Long) {
            Companion.maxMemorySize = maxMemorySize
        }
    }
}
