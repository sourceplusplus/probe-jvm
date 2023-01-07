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

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import spp.probe.ProbeConfiguration
import spp.probe.services.common.model.ClassMetadata
import java.io.File
import java.io.FileOutputStream
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

class LiveTransformer(private val className: String) : ClassFileTransformer {

    override fun transform(
        loader: ClassLoader, className: String, classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain, classfileBuffer: ByteArray
    ): ByteArray? {
        if (className.replace('/', '.') != this.className) {
            return null
        }

        val classReader = ClassReader(classfileBuffer)
        val classMetadata = ClassMetadata()
        classReader.accept(MetadataCollector(classMetadata), ClassReader.SKIP_FRAMES)
        val classWriter = ClassWriter(computeFlag(classReader))
        val classVisitor: ClassVisitor = LiveClassVisitor(classWriter, classMetadata)
        classReader.accept(classVisitor, ClassReader.SKIP_FRAMES)

        val dumpDir = ProbeConfiguration.spp.getString("transformed_dump_directory")
        if (!dumpDir.isNullOrEmpty()) {
            val dumpClassName = "${className.substringAfterLast("/")}-${System.currentTimeMillis()}.class"
            val outputFile = File(dumpDir, dumpClassName)
            outputFile.parentFile.mkdirs()

            val bytes = classWriter.toByteArray()
            FileOutputStream(outputFile).use { outputStream -> outputStream.write(bytes) }
        }
        return classWriter.toByteArray()
    }

    private fun computeFlag(classReader: ClassReader): Int {
        var flag = ClassWriter.COMPUTE_MAXS
        val version = classReader.readShort(6)
        if (version >= Opcodes.V1_7) {
            flag = ClassWriter.COMPUTE_FRAMES
        }
        return flag
    }
}
