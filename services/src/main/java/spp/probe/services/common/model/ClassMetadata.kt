package spp.probe.services.common.model

import java.util.HashMap
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes
import java.io.Serializable
import java.util.ArrayList

class ClassMetadata : Serializable {
    val fields: MutableList<ClassField>
    val staticFields: MutableList<ClassField>
    val variables: MutableMap<String?, MutableList<LocalVariable>>

    init {
        fields = ArrayList()
        staticFields = ArrayList()
        variables = HashMap()
    }

    fun addField(field: ClassField) {
        if (isStaticField(field)) {
            staticFields.add(field)
        } else {
            fields.add(field)
        }
    }

    fun addVariable(methodId: String?, variable: LocalVariable) {
        variables.computeIfAbsent(methodId) { k: String? -> ArrayList() }
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