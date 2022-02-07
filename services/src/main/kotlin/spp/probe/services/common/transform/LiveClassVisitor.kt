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
