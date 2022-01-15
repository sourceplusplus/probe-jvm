package spp.probe.services.common.transform

import net.bytebuddy.jar.asm.*
import spp.probe.services.common.model.ClassField
import spp.probe.services.common.model.ClassMetadata
import spp.probe.services.common.model.LocalVariable

class MetadataCollector(private val classMetadata: ClassMetadata) : ClassVisitor(ASM_VERSION) {

    companion object {
        private const val ASM_VERSION = Opcodes.ASM7
    }

    override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor? {
        classMetadata.addField(ClassField(access, name, desc))
        return super.visitField(access, name, desc, signature, value)
    }

    override fun visitMethod(
        access: Int, methodName: String, desc: String, signature: String?, exceptions: Array<out String>?
    ): MethodVisitor {
        val superMV = super.visitMethod(access, methodName, desc, signature, exceptions)
        val methodUniqueName = methodName + desc
        return object : MethodVisitor(ASM_VERSION, superMV) {
            private val labelLineMapping: MutableMap<String, Int> = HashMap()

            override fun visitLineNumber(line: Int, start: Label) {
                labelLineMapping[start.toString()] = line
            }

            override fun visitLocalVariable(
                name: String, desc: String, signature: String?,
                start: Label, end: Label, index: Int
            ) {
                super.visitLocalVariable(name, desc, signature, start, end, index)
                classMetadata.addVariable(
                    methodUniqueName,
                    LocalVariable(name, desc, labelLine(start), labelLine(end), index)
                )
            }

            private fun labelLine(label: Label): Int {
                val labelId = label.toString()
                return if (labelLineMapping.containsKey(labelId)) {
                    labelLineMapping[labelId]!!
                } else Int.MAX_VALUE
            }
        }
    }
}
