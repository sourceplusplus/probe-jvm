package spp.probe.services.common.transform

import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import spp.probe.services.common.model.ClassMetadata
import spp.probe.services.instrument.LiveInstrumentTransformer
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.LiveInstrumentType

class LiveClassVisitor(
    cv: ClassVisitor,
    private val instrument: LiveInstrument,
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
        return when (instrument.type) {
            LiveInstrumentType.SPAN -> {
                when {
                    name == "getSkyWalkingDynamicField" -> {
                        //ignore SkyWalking methods
                        return super.visitMethod(access, name, desc, signature, exceptions)
                    }
                    name == "setSkyWalkingDynamicField" -> {
                        //ignore SkyWalking methods
                        return super.visitMethod(access, name, desc, signature, exceptions)
                    }
                    name == "<clinit>" -> {
                        //ignore static constructor
                        return super.visitMethod(access, name, desc, signature, exceptions)
                    }
                    name == "<init>" -> {
                        //ignore constructor
                        super.visitMethod(access, name, desc, signature, exceptions)
                    }
                    name.contains("\$original\$") -> {
                        //ignore original methods
                        super.visitMethod(access, name, desc, signature, exceptions)
                    }
                    classMetadata.enhancedMethods.contains(name + desc) -> {
                        //ignore enhanced methods
                        super.visitMethod(access, name, desc, signature, exceptions)
                    }
                    else -> {
                        LiveInstrumentTransformer(
                            instrument, className, name, desc, access, classMetadata,
                            super.visitMethod(access, name, desc, signature, exceptions)
                        )
                    }
                }
            }
            else -> {
                LiveInstrumentTransformer(
                    instrument, className, name, desc, access, classMetadata,
                    super.visitMethod(access, name, desc, signature, exceptions)
                )
            }
        }
    }
}
