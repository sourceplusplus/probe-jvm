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

import com.google.gson.*
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
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
 * Like `GsonBuilder`, this API supports chaining: <pre>   `RuntimeTypeAdapterFactory<Shape> shapeAdapterFactory = RuntimeTypeAdapterFactory.of(Shape.class)
 * .registerSubtype(Rectangle.class)
 * .registerSubtype(Circle.class)
 * .registerSubtype(Diamond.class);
`</pre> *
 */
class RuntimeClassIdentityTypeAdapterFactory<T> private constructor(baseType: Class<*>?, typeFieldName: String?) :
    TypeAdapterFactory {
    private val baseType: Class<*>
    private val typeFieldName: String
    private val labelToSubtype: MutableMap<String, Class<*>> = LinkedHashMap()
    private val subtypeToLabel: MutableMap<Class<*>, String> = LinkedHashMap()

    init {
        if (typeFieldName == null || baseType == null) {
            throw NullPointerException()
        }
        this.baseType = baseType
        this.typeFieldName = typeFieldName
    }
    /**
     * Registers `type` identified by `label`. Labels are case
     * sensitive.
     *
     * @throws IllegalArgumentException if either `type` or `label`
     * have already been registered on this type adapter.
     */
    /**
     * Registers `type` identified by its [simple][Class.getSimpleName]. Labels are case sensitive.
     *
     * @throws IllegalArgumentException if either `type` or its simple name
     * have already been registered on this type adapter.
     */
    @JvmOverloads
    fun registerSubtype(
        type: Class<out T>?,
        label: String? = type!!.simpleName
    ): RuntimeClassIdentityTypeAdapterFactory<T> {
        if (type == null || label == null) {
            throw NullPointerException()
        }
        require(!(subtypeToLabel.containsKey(type) || labelToSubtype.containsKey(label))) { "types and labels must be unique" }
        labelToSubtype[label] = type
        subtypeToLabel[type] = label
        return this
    }

    override fun <R> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R> {
        val labelToDelegate: MutableMap<String, TypeAdapter<*>> = LinkedHashMap()
        val subtypeToDelegate: MutableMap<Class<*>, TypeAdapter<*>> = LinkedHashMap()

//    && !String.class.isAssignableFrom(type.getRawType())
        if (Any::class.java.isAssignableFrom(type.rawType)) {
            val delegate: TypeAdapter<*> = gson.getDelegateAdapter(this, type)
            labelToDelegate[type.rawType.name] = delegate
            subtypeToDelegate[type.rawType] = delegate
        }

//    for (Map.Entry<String, Class<?>> entry : labelToSubtype.entrySet()) {
//      TypeAdapter<?> delegate = gson.getDelegateAdapter(this, TypeToken.get(entry.getValue()));
//      labelToDelegate.put(entry.getKey(), delegate);
//      subtypeToDelegate.put(entry.getValue(), delegate);
//    }
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
                        delegate = gson.getDelegateAdapter(this@RuntimeClassIdentityTypeAdapterFactory, subClass)
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
                    val delegate = gson.getDelegateAdapter(this@RuntimeClassIdentityTypeAdapterFactory, type)
                        ?: throw JsonParseException("cannot deserialize $baseType; did you forget to register a subtype?")
                    delegate.fromJsonTree(jsonElement)
                }
            }

            @Throws(IOException::class)
            override fun write(out: JsonWriter, value: R?) {
                val srcType: Class<*> = value!!.javaClass
                val label = Integer.toHexString(System.identityHashCode(value))
                val delegate = getDelegate(srcType)
                    ?: throw JsonParseException(
                        "cannot serialize " + srcType.name
                                + "; did you forget to register a subtype?"
                    )
                val jsonTree = delegate.toJsonTree(value)
                if (!jsonTree.isJsonObject) {
                    Streams.write(jsonTree, out)
                } else {
                    val jsonObject = jsonTree.asJsonObject
                    if (jsonObject.has(typeFieldName)) {
                        Streams.write(jsonObject, out)
                    } else {
                        val clone = JsonObject()
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

    companion object {
        /**
         * Creates a new runtime type adapter using for `baseType` using `typeFieldName` as the type field name. Type field names are case sensitive.
         */
        fun <T> of(baseType: Class<T>?, typeFieldName: String?): RuntimeClassIdentityTypeAdapterFactory<T> {
            return RuntimeClassIdentityTypeAdapterFactory(baseType, typeFieldName)
        }

        /**
         * Creates a new runtime type adapter for `baseType` using `"type"` as
         * the type field name.
         */
        fun <T> of(baseType: Class<T>?): RuntimeClassIdentityTypeAdapterFactory<T> {
            return RuntimeClassIdentityTypeAdapterFactory(baseType, "@identity")
        }
    }
}