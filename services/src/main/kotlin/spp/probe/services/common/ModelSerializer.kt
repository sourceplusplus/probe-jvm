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

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import spp.probe.services.common.serialize.RuntimeClassNameTypeAdapterFactory
import spp.probe.services.common.serialize.CappedTypeAdapterFactory
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

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    val extendedGson: Gson = GsonBuilder()
        .setJsogPolicy(JsogPolicy.DEFAULT.withJsogAlwaysEnabled())
        .registerTypeAdapterFactory(CappedTypeAdapterFactory(2))
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

    fun toExtendedJson(src: Any?): String {
        return extendedGson.toJson(src)
    }
}
