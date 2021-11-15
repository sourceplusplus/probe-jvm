package spp.probe.services.common

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import spp.probe.services.common.serialize.RuntimeClassIdentityTypeAdapterFactory
import spp.probe.services.common.serialize.RuntimeClassNameTypeAdapterFactory
import spp.probe.services.common.serialize.SizeCappedTypeAdapterFactory
import java.io.IOException
import java.io.OutputStream

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
        .registerTypeAdapterFactory(SizeCappedTypeAdapterFactory())
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
        .registerTypeAdapterFactory(RuntimeClassIdentityTypeAdapterFactory.of(Any::class.java))
        .registerTypeAdapterFactory(RuntimeClassNameTypeAdapterFactory.of(Any::class.java))
        .disableHtmlEscaping().create()

    fun toJson(src: Any?): String {
        return gson.toJson(src)
    }

    fun toExtendedJson(src: Any?): String {
        return extendedGson.toJson(src)
    }
}
