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

import io.vertx.core.Vertx
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import spp.probe.ProbeConfiguration
import spp.probe.services.common.model.ClassMetadata
import spp.probe.services.instrument.LiveInstrumentService
import spp.probe.services.instrument.QueuedLiveInstrumentApplier
import java.io.File
import java.io.FileOutputStream
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

class LiveTransformer : ClassFileTransformer {

    private val log = LogManager.getLogger(LiveTransformer::class.java)
    private val hasActiveTransformations = mutableSetOf<String>()
    internal lateinit var classMetadata: ClassMetadata //visible for testing
    internal var transformAll: Boolean = false //visible for testing

    override fun transform(
        loader: ClassLoader, className: String, classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain, classfileBuffer: ByteArray
    ): ByteArray? {
        val qualifiedClassName = className.replace('/', '.')
        val classInstruments = LiveInstrumentService.getInstrumentsForClass(qualifiedClassName)
        if (classInstruments.isNotEmpty() || transformAll) {
            hasActiveTransformations.add(qualifiedClassName)
            log.info("Transforming class: $className. Active instruments: ${classInstruments.size}")
        } else if (hasActiveTransformations.remove(qualifiedClassName)) {
            log.info("Removing transformations for class: $className. Active instruments: ${classInstruments.size}")
        } else {
            return null
        }

        val workLoad = Vertx.currentContext().getLocal<QueuedLiveInstrumentApplier.WorkLoad>("workload")
        val classMetadata = ClassMetadata()
        this.classMetadata = classMetadata
        val classReader = ClassReader(classfileBuffer)
        classReader.accept(MetadataCollector(className, classMetadata), ClassReader.SKIP_FRAMES)
        workLoad.innerClasses.addAll(classMetadata.innerClasses)
        if (classMetadata.innerClasses.isNotEmpty()) {
            log.info("Found inner classes for $className: ${classMetadata.innerClasses}")
        }

        val classWriter = ClassWriter(computeFlag(classReader))
        val classVisitor = LiveClassVisitor(classWriter, classMetadata)
        try {
            classReader.accept(classVisitor, ClassReader.SKIP_FRAMES)
        } catch (t: Throwable) {
            log.error("Failed to transform class: $className", t)
            return null
        }

        val dumpDir = ProbeConfiguration.spp.getString("transformed_dump_directory")
        if (!dumpDir.isNullOrEmpty()) {
            val dumpClassName = "${className.substringAfterLast("/")}-${System.currentTimeMillis()}.class"
            val outputFile = File(dumpDir, dumpClassName)
            outputFile.parentFile.mkdirs()

            val bytes = classWriter.toByteArray()
            FileOutputStream(outputFile).use { outputStream -> outputStream.write(bytes) }
            log.debug("Dumped transformed class $className to $outputFile")
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
