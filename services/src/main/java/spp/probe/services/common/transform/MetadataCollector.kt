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
