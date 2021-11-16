package spp.probe.services.common.model

import net.bytebuddy.jar.asm.Opcodes
import java.io.Serializable

class ClassMetadata : Serializable {

    val fields: MutableList<ClassField> = mutableListOf()
    val staticFields: MutableList<ClassField> = mutableListOf()
    val variables: MutableMap<String, MutableList<LocalVariable>> = mutableMapOf()

    fun addField(field: ClassField) {
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
