package spp.probe.services.common.transform

import spp.probe.services.instrument.LiveInstrumentTransformer
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.ClassVisitor
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.MethodVisitor
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes
import spp.probe.services.common.model.ClassMetadata

class LiveClassVisitor(cv: ClassVisitor?, private val source: String, private val classMetadata: ClassMetadata) :
    ClassVisitor(
        Opcodes.ASM7, cv
    ) {
    private var className: String? = null
    override fun visit(
        version: Int, access: Int,
        name: String, signature: String, superName: String,
        interfaces: Array<String>
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        className = name
    }

    override fun visitMethod(
        access: Int,
        name: String,
        desc: String,
        signature: String,
        exceptions: Array<String>
    ): MethodVisitor {
        return LiveInstrumentTransformer(
            source, className, name, desc, access, classMetadata,
            super.visitMethod(access, name, desc, signature, exceptions)
        )
    }
}