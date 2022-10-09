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

import com.google.gson.*
import com.google.gson.internal.bind.JsogRegistry
import com.google.gson.internal.bind.JsonTreeWriter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.springframework.objenesis.instantiator.util.UnsafeUtils
import spp.probe.services.common.ModelSerializer
import java.io.IOException
import java.io.StringWriter
import java.lang.instrument.Instrumentation
import java.lang.reflect.Field
import java.lang.reflect.Modifier

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

                    if (value is Collection<*>) {
                        writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                    } else if (value is Map<*, *> && value.size > maxArraySize) {
                        jsonWriter.beginArray()
                        value.onEachIndexed { i, entry ->
                            if (i >= maxArraySize) return@onEachIndexed
                            jsonWriter.beginObject()
                            jsonWriter.name(entry.key.toString())
                            if (entry.value == null) {
                                jsonWriter.nullValue()
                            } else {
                                when (entry.value) {
                                    is Boolean -> jsonWriter.value(entry.value as Boolean)
                                    is Number -> jsonWriter.value(entry.value as Number)
                                    is Char -> jsonWriter.value(entry.value.toString())
                                    is String -> jsonWriter.value(entry.value as String)
                                    else -> doWrite(jsonWriter, entry.value, objSize)
                                }
                            }
                            jsonWriter.endObject()
                        }

                        if (value.size > maxArraySize) {
                            jsonWriter.beginObject()
                            jsonWriter.name("@skip")
                            jsonWriter.value("MAX_COLLECTION_SIZE_EXCEEDED")
                            jsonWriter.name("@skip[size]")
                            jsonWriter.value(value.size)
                            jsonWriter.name("@skip[max]")
                            jsonWriter.value(maxArraySize)
                            jsonWriter.endObject()
                        }
                        jsonWriter.endArray()
                    } else if (value!!::class.java.isArray) {
                        when (value) {
                            is BooleanArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            is ByteArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            is CharArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            is ShortArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            is IntArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            is LongArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            is FloatArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            is DoubleArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            is Array<*> -> writeCollection(jsonWriter, value.iterator(), value.size, objSize)
                            else -> throw IllegalArgumentException("Unsupported array type: " + value.javaClass.name)
                        }
                    } else {
                        doWrite(jsonWriter, value as T, value.javaClass as Class<T>, objSize)
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

            private fun writeCollection(jsonWriter: JsonWriter, value: Iterator<*>, arrSize: Int, objSize: Long) {
                jsonWriter.beginArray()
                value.withIndex().forEach { (i, it) ->
                    if (i >= maxArraySize) return@forEach
                    if (it == null) {
                        jsonWriter.nullValue()
                    } else {
                        doWrite(jsonWriter, it as T, it::class.java as Class<T>, objSize)
                    }
                }

                if (arrSize > maxArraySize) {
                    jsonWriter.beginObject()
                    jsonWriter.name("@skip")
                    jsonWriter.value("MAX_COLLECTION_SIZE_EXCEEDED")
                    jsonWriter.name("@skip[size]")
                    jsonWriter.value(arrSize)
                    jsonWriter.name("@skip[max]")
                    jsonWriter.value(maxArraySize)
                    jsonWriter.endObject()
                }
                jsonWriter.endArray()
            }

            private fun doWrite(jsonWriter: JsonWriter, value: Any?, objSize: Long) {
                try {
                    ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(
                        this@CappedTypeAdapterFactory, TypeToken.get(Any::class.java)
                    ).write(jsonWriter, value)
                } catch (e: Exception) {
                    jsonWriter.beginObject()
                    jsonWriter.name("@skip")
                    jsonWriter.value("EXCEPTION_OCCURRED")
                    jsonWriter.name("@class")
                    jsonWriter.value(value!!::class.java.name)
                    jsonWriter.name("@size")
                    jsonWriter.value(objSize.toString())
                    jsonWriter.name("@cause")
                    jsonWriter.value(e.message)
                    jsonWriter.name("@id")
                    jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
                    jsonWriter.endObject()
                }
            }

            private fun <T> doWrite(jsonWriter: JsonWriter, value: T?, javaClazz: Class<T>, objSize: Long) {
                try {
                    ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(
                        this@CappedTypeAdapterFactory, TypeToken.get(javaClazz)
                    ).write(jsonWriter, value)
                } catch (e: Exception) {
                    if (e.toString().startsWith("java.lang.reflect.InaccessibleObjectException:")) {
                        try {
                            doWriteUnsafe(jsonWriter, value)
                        } catch (e: Exception) {
                            jsonWriter.beginObject()
                            jsonWriter.name("@skip")
                            jsonWriter.value("EXCEPTION_OCCURRED")
                            jsonWriter.name("@class")
                            jsonWriter.value(value!!::class.java.name)
                            jsonWriter.name("@size")
                            jsonWriter.value(objSize.toString())
                            jsonWriter.name("@cause")
                            jsonWriter.value(e.message)
                            jsonWriter.name("@id")
                            jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
                            jsonWriter.endObject()
                        }
                        return
                    }

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

            override fun read(jsonReader: JsonReader): T? = null
        }
    }

    private fun doWriteUnsafe(jsonWriter: JsonWriter, value: Any?) {
        if (value != null && JsogRegistry.get().geId(value) != null) {
            jsonWriter.name("@ref")
            jsonWriter.value(JsogRegistry.get().geId(value))
            jsonWriter.name("@class")
            jsonWriter.value(value::class.java.name)
            return
        }
        value?.let { JsogRegistry.get().register(it) }

        if (value == null) {
            jsonWriter.nullValue()
            return
        }

        JsogRegistry.get().userData.putIfAbsent("depth", 0)
        if ((JsogRegistry.get().userData["depth"] as Int) >= maxDepth) {
            jsonWriter.name("@skip")
            jsonWriter.value("MAX_DEPTH_EXCEEDED")
            jsonWriter.name("@class")
            jsonWriter.value(value.javaClass.name)
            jsonWriter.name("@id")
            jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
            return
        }

        for (field in value::class.java.declaredFields) {
            val fieldValue = getFieldValue(field, value)
            if (fieldValue?.javaClass?.name in listOf("jdk.internal.misc.Unsafe", "sun.misc.Unsafe")) {
                continue
            }

            try {
                JsogRegistry.get().userData["depth"] = (JsogRegistry.get().userData["depth"] as Int) + 1

                if (fieldValue == null) {
                    jsonWriter.name(field.name)
                    jsonWriter.nullValue()
                } else {
                    //see if module is exported
                    val module = Class::class.java.getDeclaredMethod("getModule").invoke(fieldValue::class.java)
                    val isExported = fieldValue::class.java.`package` == null || module::class.java.getDeclaredMethod(
                        "isExported",
                        String::class.java
                    ).invoke(module, fieldValue::class.java.`package`.name) as Boolean
                    if (isExported) {
                        jsonWriter.name(field.name)
                        try {
                            ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(
                                this@CappedTypeAdapterFactory, TypeToken.get(fieldValue?.javaClass)
                            ).write(jsonWriter, fieldValue)
                        } catch (e: Exception) {
                            jsonWriter.beginObject()
                            jsonWriter.name("@skip")
                            jsonWriter.value("EXCEPTION_OCCURRED")
                            jsonWriter.name("@class")
                            jsonWriter.value(fieldValue.javaClass.name)
                            jsonWriter.name("@cause")
                            jsonWriter.value(e.message)
                            jsonWriter.name("@id")
                            jsonWriter.value(Integer.toHexString(System.identityHashCode(fieldValue)))
                            jsonWriter.endObject()
                        }
                    } else {
                        val sw = StringWriter()
                        val innerJsonWriter = JsonWriter(sw)
                        innerJsonWriter.beginObject()
                        doWriteUnsafe(innerJsonWriter, fieldValue)
                        innerJsonWriter.endObject()
                        innerJsonWriter.close()
                        if (jsonWriter is JsonTreeWriter) {
                            val jsonObject = JsonParser.parseString(sw.toString()).asJsonObject
                            jsonWriter.javaClass.getDeclaredField("product").apply {
                                isAccessible = true
                            }.set(jsonWriter, jsonObject)
                        } else {
                            jsonWriter.name(field.name)
                            jsonWriter.jsonValue(sw.toString())
                        }
                    }
                }

                JsogRegistry.get().userData["depth"] = (JsogRegistry.get().userData["depth"] as Int) - 1
            } catch (ignored: Exception) {
                ignored.printStackTrace()
            }
        }
    }

    @Suppress("unused")
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

        fun getFieldValue(field: Field, value: Any?): Any? {
            val unsafe = UnsafeUtils.getUnsafe()
            return if (Modifier.isStatic(field.modifiers)) {
                val fieldOffset = unsafe.staticFieldOffset(field)
                val fieldBase = unsafe.staticFieldBase(field) ?: throw NullPointerException("static field base is null")
                //check primitive
                if (field.type.isPrimitive) {
                    when (field.type) {
                        Boolean::class.java -> unsafe.getBoolean(fieldBase, fieldOffset)
                        Byte::class.java -> unsafe.getByte(fieldBase, fieldOffset)
                        Char::class.java -> unsafe.getChar(fieldBase, fieldOffset)
                        Short::class.java -> unsafe.getShort(fieldBase, fieldOffset)
                        Int::class.java -> unsafe.getInt(fieldBase, fieldOffset)
                        Long::class.java -> unsafe.getLong(fieldBase, fieldOffset)
                        Float::class.java -> unsafe.getFloat(fieldBase, fieldOffset)
                        Double::class.java -> unsafe.getDouble(fieldBase, fieldOffset)
                        else -> throw IllegalArgumentException("Unsupported primitive type: " + field.type.name)
                    }
                } else {
                    unsafe.getObject(fieldBase, fieldOffset)
                }
            } else {
                val fieldOffset = unsafe.objectFieldOffset(field)
                //check primitive
                if (field.type.isPrimitive) {
                    when (field.type) {
                        Boolean::class.java -> unsafe.getBoolean(value, fieldOffset)
                        Byte::class.java -> unsafe.getByte(value, fieldOffset)
                        Char::class.java -> unsafe.getChar(value, fieldOffset)
                        Short::class.java -> unsafe.getShort(value, fieldOffset)
                        Int::class.java -> unsafe.getInt(value, fieldOffset)
                        Long::class.java -> unsafe.getLong(value, fieldOffset)
                        Float::class.java -> unsafe.getFloat(value, fieldOffset)
                        Double::class.java -> unsafe.getDouble(value, fieldOffset)
                        else -> throw IllegalArgumentException("Unsupported primitive type: " + field.type.name)
                    }
                } else {
                    unsafe.getObject(value, fieldOffset)
                }
            }
        }
    }
}
