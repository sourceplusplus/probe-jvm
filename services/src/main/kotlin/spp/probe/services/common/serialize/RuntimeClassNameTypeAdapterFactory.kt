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
package spp.probe.services.common.serialize

import com.google.gson.*
import com.google.gson.internal.Streams
import com.google.gson.internal.bind.JsogRegistry
import com.google.gson.internal.bind.JsonTreeWriter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import spp.probe.ProbeConfiguration
import spp.probe.services.common.ModelSerializer
import java.io.IOException

/**
 *
 *
 * Disclaimer: taken from here https://stackoverflow.com/a/40133286/285091 with some modifications
 *
 *
 * Adapts values whose runtime type may differ from their declaration type. This
 * is necessary when a field's type is not the same type that GSON should create
 * when deserializing that field. For example, consider these types:
 * <pre>   `abstract class Shape {
 * int x;
 * int y;
 * }
 * class Circle extends Shape {
 * int radius;
 * }
 * class Rectangle extends Shape {
 * int width;
 * int height;
 * }
 * class Diamond extends Shape {
 * int width;
 * int height;
 * }
 * class Drawing {
 * Shape bottomShape;
 * Shape topShape;
 * }
`</pre> *
 *
 * Without additional type information, the serialized JSON is ambiguous. Is
 * the bottom shape in this drawing a rectangle or a diamond? <pre>   `{
 * "bottomShape": {
 * "width": 10,
 * "height": 5,
 * "x": 0,
 * "y": 0
 * },
 * "topShape": {
 * "radius": 2,
 * "x": 4,
 * "y": 1
 * }
 * }`</pre>
 * This class addresses this problem by adding type information to the
 * serialized JSON and honoring that type information when the JSON is
 * deserialized: <pre>   `{
 * "bottomShape": {
 * "type": "Diamond",
 * "width": 10,
 * "height": 5,
 * "x": 0,
 * "y": 0
 * },
 * "topShape": {
 * "type": "Circle",
 * "radius": 2,
 * "x": 4,
 * "y": 1
 * }
 * }`</pre>
 * Both the type field name (`"type"`) and the type labels (`"Rectangle"`) are configurable.
 *
 *
 * <h3>Registering Types</h3>
 * Create a `RuntimeTypeAdapterFactory` by passing the base type and type field
 * name to the [.of] factory method. If you don't supply an explicit type
 * field name, `"type"` will be used. <pre>   `RuntimeTypeAdapterFactory<Shape> shapeAdapterFactory
 * = RuntimeTypeAdapterFactory.of(Shape.class, "type");
`</pre> *
 * Next register all of your subtypes. Every subtype must be explicitly
 * registered. This protects your application from injection attacks. If you
 * don't supply an explicit type label, the type's simple name will be used.
 * <pre>   `shapeAdapter.registerSubtype(Rectangle.class, "Rectangle");
 * shapeAdapter.registerSubtype(Circle.class, "Circle");
 * shapeAdapter.registerSubtype(Diamond.class, "Diamond");
`</pre> *
 * Finally, register the type adapter factory in your application's GSON builder:
 * <pre>   `Gson gson = new GsonBuilder()
 * .registerTypeAdapterFactory(shapeAdapterFactory)
 * .create();
`</pre> *
 * Like `GsonBuilder`, this API supports chaining:
 * <pre>
 * `RuntimeTypeAdapterFactory<Shape> shapeAdapterFactory = RuntimeTypeAdapterFactory.of(Shape.class)
 * .registerSubtype(Rectangle.class)
 * .registerSubtype(Circle.class)
 * .registerSubtype(Diamond.class);
`</pre> *
 */
class RuntimeClassNameTypeAdapterFactory<T> private constructor(baseType: Class<*>?, typeFieldName: String?) :
    TypeAdapterFactory {

    private val log = LogManager.getLogger(RuntimeClassNameTypeAdapterFactory::class.java)

    private val baseType: Class<*>
    private val typeFieldName: String

    init {
        if (typeFieldName == null || baseType == null) {
            throw NullPointerException("typeFieldName == null || baseType == null")
        }
        this.baseType = baseType
        this.typeFieldName = typeFieldName
    }

    override fun <R> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R> {
        val labelToDelegate: MutableMap<String, TypeAdapter<*>> = LinkedHashMap()
        val subtypeToDelegate: MutableMap<Class<*>, TypeAdapter<*>> = LinkedHashMap()

        if (Any::class.java.isAssignableFrom(type.rawType)) {
            val delegate: TypeAdapter<*> = gson.getDelegateAdapter(this, type)
            labelToDelegate[type.rawType.name] = delegate
            subtypeToDelegate[type.rawType] = delegate
        }

        return object : TypeAdapter<R>() {
            @Throws(IOException::class)
            override fun read(`in`: JsonReader): R? {
                val jsonElement = Streams.parse(`in`)
                return if (jsonElement.isJsonObject) {
                    val labelJsonElement = jsonElement.asJsonObject.remove(typeFieldName)
                        ?: throw JsonParseException(
                            "cannot deserialize " + baseType
                                    + " because it does not define a field named " + typeFieldName
                        )
                    val label = labelJsonElement.asString
                    var delegate = labelToDelegate[label] as TypeAdapter<R>?
                    if (delegate == null) {
                        val aClass: Class<R>
                        aClass = try {
                            Class.forName(label) as Class<R>
                        } catch (e: ClassNotFoundException) {
                            throw JsonParseException("Cannot find class $label", e)
                        }
                        val subClass = TypeToken.get(aClass)
                        delegate = gson.getDelegateAdapter(this@RuntimeClassNameTypeAdapterFactory, subClass)
                        if (delegate == null) {
                            throw JsonParseException(
                                "cannot deserialize " + baseType + " subtype named "
                                        + label + "; did you forget to register a subtype?"
                            )
                        }
                    }
                    delegate.fromJsonTree(jsonElement)
                } else if (jsonElement.isJsonNull) {
                    null
                } else {
                    val delegate = gson.getDelegateAdapter(
                        this@RuntimeClassNameTypeAdapterFactory, type
                    ) ?: throw JsonParseException("cannot deserialize $baseType; did you forget to register a subtype?")
                    delegate.fromJsonTree(jsonElement)
                }
            }

            @Throws(IOException::class)
            override fun write(out: JsonWriter, value: R?) {
                val srcType: Class<*> = value!!.javaClass
                val label = srcType.name
                val delegate = getDelegate(srcType)
                    ?: throw JsonParseException(
                        "cannot serialize " + srcType.name
                                + "; did you forget to register a subtype?"
                    )
                JsogRegistry.get().userData["variable_name"] = getPendingName(out)
                val jsonTree = delegate.toJsonTree(value)
                if (!jsonTree.isJsonObject) {
                    Streams.write(jsonTree, out)
                } else {
                    val jsonObject = jsonTree.asJsonObject
                    if (jsonObject.has(typeFieldName)) {
                        Streams.write(jsonObject, out)
                    } else {
                        val clone = JsonObject()

                        //search for self-references
                        try {
                            srcType.declaredFields.forEach {
                                val fieldValue = try {
                                    it.isAccessible = true
                                    it.get(value)
                                } catch (ignored: Exception) {
                                    CappedTypeAdapterFactory.getFieldValue(it, value)
                                }
                                if (fieldValue === value) {
                                    val selfRef = JsonObject()
                                    selfRef.addProperty("@ref", JsogRegistry.get().geId(value))
                                    selfRef.addProperty("@class", value.javaClass.name)
                                    clone.add(it.name, selfRef)
                                }
                            }
                        } catch (e: Throwable) {
                            log.error("Error while serializing self-references", e)
                        }

                        clone.add(typeFieldName, JsonPrimitive(label))
                        for ((key, value1) in jsonObject.entrySet()) {
                            clone.add(key, value1)
                        }
                        Streams.write(clone, out)
                    }
                }
            }

            private fun getDelegate(srcType: Class<*>): TypeAdapter<R?>? {
                val typeAdapter = subtypeToDelegate[srcType]
                if (typeAdapter != null) {
                    return typeAdapter as TypeAdapter<R?>?
                }
                for ((key, value) in subtypeToDelegate) {
                    if (key.isAssignableFrom(srcType)) {
                        return value as TypeAdapter<R?>
                    }
                }
                return null
            }
        }.nullSafe()
    }

    fun getPendingName(jsonWriter: JsonWriter): String? {
        //check if variable name is necessary
        val liveBreakpoint = ModelSerializer.INSTANCE.rootBreakpoint.get()
        val usesBreakpointByName = liveBreakpoint?.variableControl?.variableNameConfig?.isNotEmpty() == true
        val usesConfigByName = ProbeConfiguration.variableControlByName.isNotEmpty()
        if (!usesBreakpointByName && !usesConfigByName) {
            return null
        }

        if (jsonWriter is JsonTreeWriter) {
            jsonWriter::class.java.getDeclaredField("pendingName").apply {
                isAccessible = true
                return get(jsonWriter) as String?
            }
        }
        return null
    }

    companion object {

        /**
         * Creates a new runtime type adapter for `baseType` using `"type"` as
         * the type field name.
         */
        fun <T> of(baseType: Class<T>?): RuntimeClassNameTypeAdapterFactory<T> {
            return RuntimeClassNameTypeAdapterFactory(baseType, "@class")
        }
    }
}
