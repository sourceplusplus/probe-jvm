package spp.probe.services.common.transform

import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.*
import spp.probe.services.common.model.ClassMetadata
import spp.protocol.instrument.LiveSourceLocation
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

class LiveTransformer(private val location: LiveSourceLocation) : ClassFileTransformer {

    override fun transform(
        loader: ClassLoader, className: String, classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain, classfileBuffer: ByteArray
    ): ByteArray? {
        if (className.replace('/', '.') != location.source) {
            return null
        }

        val classReader = ClassReader(classfileBuffer)
        val classMetadata = ClassMetadata()
        classReader.accept(MetadataCollector(classMetadata), ClassReader.SKIP_FRAMES)
        val classWriter = ClassWriter(computeFlag(classReader))
        val classVisitor: ClassVisitor = LiveClassVisitor(classWriter, location, classMetadata)
        classReader.accept(classVisitor, ClassReader.SKIP_FRAMES)
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

    companion object {
        fun boxIfNecessary(mv: MethodVisitor, desc: String) {
            when (Type.getType(desc).sort) {
                Type.BOOLEAN -> mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Boolean",
                    "valueOf",
                    "(Z)Ljava/lang/Boolean;",
                    false
                )
                Type.BYTE -> mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Byte",
                    "valueOf",
                    "(B)Ljava/lang/Byte;",
                    false
                )
                Type.CHAR -> mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Character",
                    "valueOf",
                    "(C)Ljava/lang/Character;",
                    false
                )
                Type.DOUBLE -> mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "valueOf",
                    "(D)Ljava/lang/Double;",
                    false
                )
                Type.FLOAT -> mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "valueOf",
                    "(F)Ljava/lang/Float;",
                    false
                )
                Type.INT -> mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Integer",
                    "valueOf",
                    "(I)Ljava/lang/Integer;",
                    false
                )
                Type.LONG -> mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Long",
                    "valueOf",
                    "(J)Ljava/lang/Long;",
                    false
                )
                Type.SHORT -> mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Short",
                    "valueOf",
                    "(S)Ljava/lang/Short;",
                    false
                )
            }
        }
    }
}
