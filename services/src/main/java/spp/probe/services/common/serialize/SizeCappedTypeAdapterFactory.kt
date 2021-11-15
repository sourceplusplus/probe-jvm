package spp.probe.services.common.serialize

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import spp.probe.services.common.ModelSerializer
import java.io.IOException
import java.lang.instrument.Instrumentation

class SizeCappedTypeAdapterFactory : TypeAdapterFactory {

    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        return if (instrumentation == null || maxMemorySize == -1L) null else object : TypeAdapter<T>() {
            @Throws(IOException::class)
            override fun write(jsonWriter: JsonWriter, value: T?) {
                val objSize = instrumentation!!.getObjectSize(value)
                if (objSize <= maxMemorySize) {
                    ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(this@SizeCappedTypeAdapterFactory, type)
                        .write(jsonWriter, value)
                } else {
                    jsonWriter.beginObject()
                    jsonWriter.name("@class")
                    jsonWriter.value("LargeObject")
                    jsonWriter.name("@size")
                    jsonWriter.value(objSize.toString())
                    jsonWriter.name("@identity")
                    jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
                    jsonWriter.endObject()
                }
            }

            override fun read(jsonReader: JsonReader): T? {
                return null
            }
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