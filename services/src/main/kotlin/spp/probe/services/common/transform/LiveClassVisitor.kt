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

import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import spp.probe.services.common.model.ClassMetadata
import spp.probe.services.instrument.LiveInstrumentTransformer

class LiveClassVisitor(
    cv: ClassVisitor,
    private val classMetadata: ClassMetadata
) : ClassVisitor(Opcodes.ASM7, cv) {

    private lateinit var className: String

    override fun visit(
        version: Int, access: Int, name: String, signature: String?, superName: String, interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        className = name
    }

    override fun visitMethod(
        access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?
    ): MethodVisitor {
        return LiveInstrumentTransformer(
            className, name, desc, access, classMetadata, super.visitMethod(access, name, desc, signature, exceptions)
        )
    }
}
