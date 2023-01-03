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

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import spp.probe.services.common.serialize.CappedTypeAdapterFactory
import spp.probe.services.common.serialize.RuntimeClassNameTypeAdapterFactory
import spp.protocol.instrument.LiveBreakpoint
import java.io.IOException
import java.io.OutputStream
import java.util.*

enum class ModelSerializer {
    INSTANCE;

    companion object {
        private val ignoredTypes: MutableSet<String> = HashSet()

        init {
            ignoredTypes.add("org.apache.skywalking.apm.plugin.spring.mvc.commons.EnhanceRequireObjectCache")
        }
    }

    val rootVariableName = ThreadLocal<String?>()
    val rootBreakpoint = ThreadLocal<LiveBreakpoint?>()
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    val extendedGson: Gson = GsonBuilder()
        .serializeNulls()
        .setJsogPolicy(JsogPolicy.DEFAULT.withJsogAlwaysEnabled())
        .registerTypeAdapterFactory(CappedTypeAdapterFactory())
        .registerTypeAdapterFactory(object : TypeAdapterFactory {
            override fun <T> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
                return if (ignoredTypes.contains(typeToken.rawType.name)) {
                    object : TypeAdapter<T>() {
                        override fun write(jsonWriter: JsonWriter, ignored: T?) {}
                        override fun read(jsonReader: JsonReader): T? {
                            return null
                        }
                    }
                } else null
            }
        })
        .registerTypeAdapterFactory(object : TypeAdapterFactory {
            override fun <T> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
                return if (OutputStream::class.java.isAssignableFrom(typeToken.rawType)) {
                    object : TypeAdapter<OutputStream?>() {
                        @Throws(IOException::class)
                        override fun write(jsonWriter: JsonWriter, outputStream: OutputStream?) {
                            jsonWriter.beginObject()
                            jsonWriter.endObject()
                        }

                        override fun read(jsonReader: JsonReader): OutputStream? {
                            return null
                        }
                    } as TypeAdapter<T>
                } else null
            }
        })
        .registerTypeAdapterFactory(RuntimeClassNameTypeAdapterFactory.of(Any::class.java))
        .disableHtmlEscaping().create()

    fun toJson(src: Any?): String {
        return gson.toJson(src)
    }

    fun toExtendedJson(src: Any?, varName: String? = null, breakpoint: LiveBreakpoint? = null): String {
        try {
            rootVariableName.set(varName)
            rootBreakpoint.set(breakpoint)
            return extendedGson.toJson(src)
        } finally {
            rootVariableName.remove()
            rootBreakpoint.remove()
        }
    }
}
