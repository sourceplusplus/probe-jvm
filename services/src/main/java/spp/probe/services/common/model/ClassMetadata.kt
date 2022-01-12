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
    val enhancedMethods: MutableList<String> = mutableListOf()
    val onlyThrowsMethods: MutableList<String> = mutableListOf()

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
        //todo: ignore skywalking variables
    }

    private fun isStaticField(field: ClassField): Boolean {
        return field.access and Opcodes.ACC_STATIC != 0
    }

    override fun toString(): String {
        return "ClassMetadata{" +
                "fields=" + fields +
                ", staticFields=" + staticFields +
                ", variables=" + variables +
                ", enhancedMethods=" + enhancedMethods +
                ", onlyThrowsMethods=" + onlyThrowsMethods +
                '}'
    }
}
