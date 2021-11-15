package spp.probe.services.instrument

import spp.probe.services.common.ProbeMemory
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.meter.LiveMeter
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Label
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.MethodVisitor
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Type
import spp.probe.services.common.transform.LiveTransformer
import spp.protocol.instrument.log.LiveLog
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.probe.services.common.model.ClassMetadata

class LiveInstrumentTransformer(
    private val source: String, private val className: String?, methodName: String, desc: String, access: Int,
    classMetadata: ClassMetadata, mv: MethodVisitor?
) : MethodVisitor(Opcodes.ASM7, mv) {
    private val methodUniqueName: String
    private val access: Int
    private val classMetadata: ClassMetadata

    init {
        methodUniqueName = methodName + desc
        this.access = access
        this.classMetadata = classMetadata
    }

    override fun visitLineNumber(line: Int, start: Label) {
        mv.visitLineNumber(line, start)
        for (instrument in LiveInstrumentService.getInstruments(
            LiveSourceLocation(
                source, line
            )
        )) {
            val instrumentLabel = Label()
            isInstrumentEnabled(instrument.instrument.id, instrumentLabel)
            if (instrument.instrument is LiveBreakpoint) {
                captureSnapshot(instrument.instrument.id, line)
                isHit(instrument.instrument.id, instrumentLabel)
                putBreakpoint(instrument.instrument.id, source, line)
            } else if (instrument.instrument is LiveLog) {
                val log = instrument.instrument
                if (log!!.logArguments.size > 0 || instrument.expression != null) {
                    captureSnapshot(log.id, line)
                }
                isHit(log.id, instrumentLabel)
                putLog(log)
            } else if (instrument.instrument is LiveMeter) {
                val meter = instrument.instrument
                if (instrument.expression != null) {
                    captureSnapshot(meter!!.id, line)
                }
                isHit(meter!!.id, instrumentLabel)
                putMeter(meter)
            }
            mv.visitLabel(Label())
            mv.visitLabel(instrumentLabel)
        }
    }

    private fun isInstrumentEnabled(instrumentId: String?, instrumentLabel: Label) {
        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION, "isInstrumentEnabled",
            REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(Opcodes.IFEQ, instrumentLabel)
    }

    private fun captureSnapshot(instrumentId: String?, line: Int) {
        addLocals(instrumentId, line)
        addStaticFields(instrumentId)
        addFields(instrumentId)
    }

    private fun isHit(instrumentId: String?, instrumentLabel: Label) {
        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION, "isHit",
            REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(Opcodes.IFEQ, instrumentLabel)
    }

    private fun addLocals(instrumentId: String?, line: Int) {
        for (`var` in classMetadata.variables[methodUniqueName]!!) {
            if (line >= `var`.start && line < `var`.end) {
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(`var`.name)
                mv.visitVarInsn(Type.getType(`var`.desc).getOpcode(Opcodes.ILOAD), `var`.index)
                LiveTransformer.Companion.boxIfNecessary(mv, `var`.desc)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
                    "putLocalVariable", REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun addStaticFields(instrumentId: String?) {
        for (field in classMetadata.staticFields) {
            mv.visitLdcInsn(instrumentId)
            mv.visitLdcInsn(field.name)
            mv.visitFieldInsn(Opcodes.GETSTATIC, className, field.name, field.desc)
            LiveTransformer.Companion.boxIfNecessary(mv, field.desc)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
                "putStaticField", REMOTE_SAVE_VAR_DESC, false
            )
        }
    }

    private fun addFields(instrumentId: String?) {
        if (access and Opcodes.ACC_STATIC == 0) {
            for (field in classMetadata.fields) {
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(field.name)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(Opcodes.GETFIELD, className, field.name, field.desc)
                LiveTransformer.Companion.boxIfNecessary(mv, field.desc)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
                    "putField", REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun putBreakpoint(instrumentId: String?, source: String, line: Int) {
        mv.visitLdcInsn(instrumentId)
        mv.visitLdcInsn(source)
        mv.visitLdcInsn(line)
        mv.visitTypeInsn(Opcodes.NEW, THROWABLE_INTERNAL_NAME)
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, THROWABLE_INTERNAL_NAME,
            "<init>",
            "()V",
            false
        )
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
            "putBreakpoint",
            "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/Throwable;)V", false
        )
    }

    private fun putLog(log: LiveLog?) {
        mv.visitLdcInsn(log!!.id)
        mv.visitLdcInsn(log.logFormat)
        mv.visitIntInsn(Opcodes.BIPUSH, log.logArguments.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
        for (i in log.logArguments.indices) {
            mv.visitInsn(Opcodes.DUP)
            mv.visitIntInsn(Opcodes.BIPUSH, i)
            mv.visitLdcInsn(log.logArguments[i])
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION, "putLog", PUT_LOG_DESC, false)
    }

    private fun putMeter(meter: LiveMeter?) {
        ProbeMemory.put("spp.live-meter:" + meter!!.id, meter)
        mv.visitLdcInsn(meter.id)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION, "putMeter", "(Ljava/lang/String;)V", false)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        mv.visitMaxs(Math.max(maxStack, 4), maxLocals)
    }

    companion object {
        private val THROWABLE_INTERNAL_NAME = Type.getInternalName(
            Throwable::class.java
        )
        private const val REMOTE_CLASS_LOCATION = "spp/probe/control/LiveInstrumentRemote"
        private const val REMOTE_CHECK_DESC = "(Ljava/lang/String;)Z"
        private const val REMOTE_SAVE_VAR_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V"
        private const val PUT_LOG_DESC = "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V"
    }
}