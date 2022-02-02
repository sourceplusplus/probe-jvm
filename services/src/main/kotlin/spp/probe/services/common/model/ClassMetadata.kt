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
