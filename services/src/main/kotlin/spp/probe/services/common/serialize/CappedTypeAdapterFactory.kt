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
package spp.probe.services.common.serialize

import com.google.gson.*
import com.google.gson.internal.bind.JsogRegistry
import com.google.gson.internal.bind.JsonTreeWriter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import net.bytebuddy.jar.asm.Type
import org.springframework.objenesis.instantiator.util.UnsafeUtils
import spp.probe.ProbeConfiguration
import spp.probe.services.common.ModelSerializer
import java.io.IOException
import java.io.StringWriter
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class CappedTypeAdapterFactory : TypeAdapterFactory {

    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
        return object : TypeAdapter<T>() {

            @Throws(IOException::class)
            override fun write(jsonWriter: JsonWriter, value: T?) {
                doWrite(value, jsonWriter)
            }

            private fun doWrite(value: Any?, jsonWriter: JsonWriter) {
                if (value == null) {
                    jsonWriter.nullValue()
                    return
                } else if (value is Class<*>) {
                    jsonWriter.value(value.name)
                    return
                }

                //get variable name (if avail)
                val variableName = JsogRegistry.get().userData["variable_name"] as String?
                    ?: ModelSerializer.INSTANCE.rootVariableName.get()

                //set remaining depth using default
                JsogRegistry.get().userData.putIfAbsent("depth", getDefaultMaxDepth())

                //use custom max depth if available
                val customMaxDepth = getCustomMaxDepth(variableName, value)
                if (customMaxDepth != 0) {
                    JsogRegistry.get().userData["reset_depth"] = JsogRegistry.get().userData["depth"] as Int
                    JsogRegistry.get().userData["depth"] = customMaxDepth
                }

                if ((JsogRegistry.get().userData["depth"] as Int) == 0) {
                    appendMaxDepthExceeded(jsonWriter, value)
                    return
                }

                val objSize = ProbeConfiguration.instrumentation!!.getObjectSize(value)
                val maxObjSize = getMaxSize(variableName, value)
                if (objSize > maxObjSize) {
                    appendMaxSizeExceeded(jsonWriter, value, objSize, maxObjSize)
                    return
                }
                val maxLength = getMaxLength(variableName, value)

                JsogRegistry.get().userData["depth"] = (JsogRegistry.get().userData["depth"] as Int) - 1

                if (value is Collection<*>) {
                    writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                } else if (value is Map<*, *>) {
                    jsonWriter.beginObject()
                    jsonWriter.name("@id")
                    jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
                    jsonWriter.name("@class")
                    jsonWriter.value(value::class.java.name)

                    value.onEachIndexed { i, entry ->
                        if (i >= maxLength) return@onEachIndexed
                        jsonWriter.name(entry.key.toString())
                        if (entry.value == null) {
                            jsonWriter.nullValue()
                        } else {
                            when (entry.value) {
                                is Boolean -> jsonWriter.value(entry.value as Boolean)
                                is Number -> jsonWriter.value(entry.value as Number)
                                is Char -> jsonWriter.value(entry.value.toString())
                                is String -> jsonWriter.value(entry.value as String)
                                is Any -> doWrite(jsonWriter, entry.value!!, entry.value!!.javaClass, objSize)
                                else -> jsonWriter.nullValue()
                            }
                        }
                    }

                    if (value.size > maxLength) {
                        appendMaxLengthExceeded(jsonWriter, value.size, maxLength, false)
                    }
                    jsonWriter.endObject()
                } else if (value::class.java.isArray) {
                    when (value) {
                        is BooleanArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        is ByteArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        is CharArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        is ShortArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        is IntArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        is LongArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        is FloatArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        is DoubleArray -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        is Array<*> -> writeCollection(jsonWriter, value.iterator(), value.size, objSize, maxLength)
                        else -> throw IllegalArgumentException("Unsupported array type: " + value.javaClass.name)
                    }
                } else {
                    if (isExported(value)) {
                        doWrite(jsonWriter, value as T, value.javaClass as Class<T>, objSize)
                    } else {
                        val sw = StringWriter()
                        val innerJsonWriter = JsonWriter(sw)
                        innerJsonWriter.beginObject()
                        for (field in value::class.java.declaredFields) {
                            val fieldValue = getFieldValue(field, value)
                            if (fieldValue != null && JsogRegistry.get().geId(fieldValue) != null) {
                                innerJsonWriter.name(field.name)
                                innerJsonWriter.beginObject()
                                innerJsonWriter.name("@ref")
                                innerJsonWriter.value(JsogRegistry.get().geId(fieldValue))
                                innerJsonWriter.name("@class")
                                innerJsonWriter.value(fieldValue::class.java.name)
                                innerJsonWriter.endObject()
                                continue
                            }
                            fieldValue?.let { JsogRegistry.get().register(it) }

                            innerJsonWriter.name("@id")
                            innerJsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
                            innerJsonWriter.name("@class")
                            innerJsonWriter.value(value::class.java.name)
                            innerJsonWriter.name(field.name)
                            doWrite(fieldValue, innerJsonWriter)
                        }
                        innerJsonWriter.endObject()
                        innerJsonWriter.close()
                        if (jsonWriter is JsonTreeWriter) {
                            val jsonObject = JsonParser.parseString(sw.toString()).asJsonObject
                            jsonWriter.javaClass.getDeclaredField("product").apply {
                                isAccessible = true
                            }.set(jsonWriter, jsonObject)
                        } else {
                            jsonWriter.jsonValue(sw.toString())
                        }
                    }
                }

                JsogRegistry.get().userData["depth"] = (JsogRegistry.get().userData["depth"] as Int) + 1

                //reset remaining depth once we come out of the custom max depth
                if (customMaxDepth != 0 && JsogRegistry.get().userData["depth"] == customMaxDepth) {
                    JsogRegistry.get().userData["depth"] = JsogRegistry.get().userData["reset_depth"]
                    JsogRegistry.get().userData.remove("reset_depth")
                }
            }

            private fun writeCollection(
                jsonWriter: JsonWriter,
                value: Iterator<*>,
                arrSize: Int,
                objSize: Long,
                maxLength: Int
            ) {
                jsonWriter.beginArray()
                value.withIndex().forEach { (i, it) ->
                    if (i >= maxLength) return@forEach
                    if (it == null) {
                        jsonWriter.nullValue()
                    } else {
                        doWrite(jsonWriter, it as T, it::class.java as Class<T>, objSize)
                    }
                }

                if (arrSize > maxLength) {
                    appendMaxLengthExceeded(jsonWriter, arrSize, maxLength)
                }
                jsonWriter.endArray()
            }

            private fun <T> doWrite(jsonWriter: JsonWriter, value: T?, javaClazz: Class<T>, objSize: Long) {
                try {
                    ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(
                        this@CappedTypeAdapterFactory, TypeToken.get(javaClazz)
                    ).write(jsonWriter, value)
                } catch (e: Exception) {
                    appendExceptionOccurred(jsonWriter, value, objSize, e)
                }
            }

            override fun read(jsonReader: JsonReader): T? = null
        }
    }

    /**
     * @param newObject maps write exception to self, other collections create new object
     */
    private fun appendMaxLengthExceeded(jsonWriter: JsonWriter, size: Int, maxLength: Int, newObject: Boolean = true) {
        if (newObject) jsonWriter.beginObject()
        jsonWriter.name("@skip")
        jsonWriter.value("MAX_LENGTH_EXCEEDED")
        jsonWriter.name("@skip[size]")
        jsonWriter.value(size)
        jsonWriter.name("@skip[max]")
        jsonWriter.value(maxLength)
        if (newObject) jsonWriter.endObject()
    }

    private fun appendMaxSizeExceeded(jsonWriter: JsonWriter, value: Any, objSize: Long, maxObjectSize: Int) {
        jsonWriter.beginObject()
        jsonWriter.name("@skip")
        jsonWriter.value("MAX_SIZE_EXCEEDED")
        jsonWriter.name("@class")
        jsonWriter.value(value::class.java.name)
        jsonWriter.name("@skip[size]")
        jsonWriter.value(objSize)
        jsonWriter.name("@skip[max]")
        jsonWriter.value(maxObjectSize)
        jsonWriter.name("@id")
        jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
        jsonWriter.endObject()
    }

    private fun appendMaxDepthExceeded(jsonWriter: JsonWriter, value: Any) {
        jsonWriter.beginObject()
        jsonWriter.name("@skip")
        jsonWriter.value("MAX_DEPTH_EXCEEDED")
        jsonWriter.name("@class")
        jsonWriter.value(value.javaClass.name)
        jsonWriter.name("@size")
        jsonWriter.value(ProbeConfiguration.instrumentation!!.getObjectSize(value))
        jsonWriter.name("@id")
        jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
        jsonWriter.endObject()
    }

    private fun appendExceptionOccurred(jsonWriter: JsonWriter, value: Any?, objSize: Long, e: Exception) {
        jsonWriter.beginObject()
        jsonWriter.name("@skip")
        jsonWriter.value("EXCEPTION_OCCURRED")
        jsonWriter.name("@class")
        jsonWriter.value(value!!::class.java.name)
        jsonWriter.name("@size")
        jsonWriter.value(objSize)
        jsonWriter.name("@cause")
        jsonWriter.value(e.message)
        jsonWriter.name("@id")
        jsonWriter.value(Integer.toHexString(System.identityHashCode(value)))
        jsonWriter.endObject()
    }

    private fun isExported(value: Any): Boolean {
        val module = Class::class.java.getDeclaredMethod("getModule").invoke(value::class.java)
        return value::class.java.`package` == null ||
                module::class.java.getDeclaredMethod("isExported", String::class.java)
                    .invoke(module, value::class.java.`package`.name) as Boolean
    }

    @Suppress("unused")
    companion object {
        fun getMaxSize(variableName: String?, value: Any): Int {
            //check live breakpoint config
            ModelSerializer.INSTANCE.rootBreakpoint.get()?.variableControl?.let {
                if (variableName != null) {
                    it.variableNameConfig[variableName]?.let {
                        it.maxObjectSize?.let { return it }
                    }
                }
                it.variableTypeConfig[getTypeName(value)]?.let {
                    it.maxObjectSize?.let { return it }
                }
                it.maxObjectSize?.let { return it }
            }

            //check spp-probe.yml config
            val defaultMax = ProbeConfiguration.variableControl.getInteger("max_object_size")
            if (variableName != null) {
                ProbeConfiguration.variableControlByName[variableName]?.let {
                    return it.getInteger("max_object_size", defaultMax)
                }
            }
            ProbeConfiguration.variableControlByType[getTypeName(value)]?.let {
                return it.getInteger("max_object_size", defaultMax)
            }
            return defaultMax
        }

        fun getMaxLength(variableName: String?, value: Any): Int {
            //check live breakpoint config
            ModelSerializer.INSTANCE.rootBreakpoint.get()?.variableControl?.let {
                if (variableName != null) {
                    it.variableNameConfig[variableName]?.let {
                        it.maxCollectionLength?.let { return it }
                    }
                }
                it.variableTypeConfig[getTypeName(value)]?.let {
                    it.maxCollectionLength?.let { return it }
                }
                it.maxCollectionLength?.let { return it }
            }

            //check spp-probe.yml config
            val defaultMax = ProbeConfiguration.variableControl.getInteger("max_collection_length")
            if (variableName != null) {
                ProbeConfiguration.variableControlByName[variableName]?.let {
                    return it.getInteger("max_collection_length", defaultMax)
                }
            }
            ProbeConfiguration.variableControlByType[getTypeName(value)]?.let {
                return it.getInteger("max_collection_length", defaultMax)
            }
            return defaultMax
        }

        fun getDefaultMaxDepth(): Int {
            //check live breakpoint config (only default max depth)
            ModelSerializer.INSTANCE.rootBreakpoint.get()?.variableControl?.let {
                it.maxObjectDepth?.let { return it }
            }

            return ProbeConfiguration.variableControl.getInteger("max_object_depth")
        }

        fun getCustomMaxDepth(variableName: String?, value: Any): Int {
            //check live breakpoint config (minus default max depth)
            ModelSerializer.INSTANCE.rootBreakpoint.get()?.variableControl?.let {
                if (variableName != null) {
                    it.variableNameConfig[variableName]?.let {
                        it.maxObjectDepth?.let { return it }
                    }
                }
                it.variableTypeConfig[getTypeName(value)]?.let {
                    it.maxObjectDepth?.let { return it }
                }
            }

            //check spp-probe.yml config
            if (variableName != null) {
                ProbeConfiguration.variableControlByName[variableName]?.let {
                    return it.getInteger("max_object_depth", 0)
                }
            }
            ProbeConfiguration.variableControlByType[getTypeName(value)]?.let {
                return it.getInteger("max_object_depth", 0)
            }
            return 0
        }

        private fun getTypeName(value: Any): String {
            if (value::class.java.isArray) {
                return Type.getType(value::class.java.name).className
            }
            return value::class.java.name
        }

        fun getFieldValue(field: Field, value: Any?): Any? {
            val unsafe = UnsafeUtils.getUnsafe()
            return if (Modifier.isStatic(field.modifiers)) {
                val fieldOffset = unsafe.staticFieldOffset(field)
                val fieldBase = unsafe.staticFieldBase(field) ?: throw NullPointerException("static field base is null")
                getFieldValue(field, fieldBase, fieldOffset)
            } else {
                val fieldOffset = unsafe.objectFieldOffset(field)
                getFieldValue(field, value, fieldOffset)
            }
        }

        private fun getFieldValue(field: Field, value: Any?, fieldOffset: Long): Any? {
            val unsafe = UnsafeUtils.getUnsafe()
            return if (field.type.isPrimitive) {
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
