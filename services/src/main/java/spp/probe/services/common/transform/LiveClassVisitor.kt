package spp.probe.services.common.transform

import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.ClassVisitor
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.MethodVisitor
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes
import spp.probe.services.common.model.ClassMetadata
import spp.probe.services.instrument.LiveInstrumentTransformer
import spp.protocol.instrument.LiveSourceLocation

class LiveClassVisitor(
    cv: ClassVisitor?,
    private val location: LiveSourceLocation,
    private val classMetadata: ClassMetadata
) : ClassVisitor(Opcodes.ASM7, cv) {

    private var className: String? = null

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
            location, className, name, desc, access, classMetadata,
            super.visitMethod(access, name, desc, signature, exceptions)
        )
    }
}
