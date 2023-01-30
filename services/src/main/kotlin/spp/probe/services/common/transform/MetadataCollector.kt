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
package spp.probe.services.common.transform

import net.bytebuddy.jar.asm.*
import spp.probe.services.common.model.ClassField
import spp.probe.services.common.model.ClassMetadata
import spp.probe.services.common.model.LocalVariable

class MetadataCollector(
    private val className: String,
    private val classMetadata: ClassMetadata
) : ClassVisitor(ASM_VERSION) {

    companion object {
        private const val ASM_VERSION = Opcodes.ASM7
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        if (name.startsWith(className) && name != className) {
            classMetadata.addInnerClass(name)
        }
        super.visitInnerClass(name, outerName, innerName, access)
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
            private var currentLine = 1

            override fun visitLabel(label: Label) {
                labelLineMapping[label.toString()] = currentLine
                super.visitLabel(label)
            }

            override fun visitLineNumber(line: Int, start: Label) {
                currentLine = line
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
