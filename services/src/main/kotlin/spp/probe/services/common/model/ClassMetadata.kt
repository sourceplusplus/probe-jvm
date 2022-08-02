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
package spp.probe.services.common.model

import net.bytebuddy.jar.asm.Opcodes
import java.io.Serializable
import java.util.regex.Pattern

class ClassMetadata : Serializable {

    companion object {
        private val ignoredVariables = Pattern.compile(
            "(_\\\$EnhancedClassField_ws)|((delegate|cachedValue)\\$[a-zA-Z0-9\$]+)"
        )
    }

    val fields: MutableList<ClassField> = mutableListOf()
    val staticFields: MutableList<ClassField> = mutableListOf()
    val variables: MutableMap<String, MutableList<LocalVariable>> = mutableMapOf()

    fun addField(field: ClassField) {
        if (ignoredVariables.matcher(field.name).matches()) {
            return
        }

        if (isStaticField(field)) {
            staticFields.add(field)
        } else {
            fields.add(field)
        }
    }

    fun addVariable(methodId: String, variable: LocalVariable) {
        variables.computeIfAbsent(methodId) { ArrayList() }
        variables[methodId]!!.add(variable)
    }

    private fun isStaticField(field: ClassField): Boolean {
        return field.access and Opcodes.ACC_STATIC != 0
    }

    override fun toString(): String {
        return "ClassMetadata{" +
                "fields=" + fields +
                ", staticFields=" + staticFields +
                ", variables=" + variables +
                '}'
    }
}
