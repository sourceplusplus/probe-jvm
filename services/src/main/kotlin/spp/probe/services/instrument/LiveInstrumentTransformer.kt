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
package spp.probe.services.instrument

import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import spp.probe.services.common.ProbeMemory
import spp.probe.services.common.model.ClassMetadata
import spp.probe.services.common.transform.LiveTransformer
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.LiveSpan

class LiveInstrumentTransformer(
    private val className: String,
    methodName: String,
    desc: String,
    access: Int,
    classMetadata: ClassMetadata,
    mv: MethodVisitor
) : MethodVisitor(Opcodes.ASM7, mv) {

    companion object {
        private val THROWABLE_INTERNAL_NAME = Type.getInternalName(Throwable::class.java)
        const val REMOTE_CLASS_LOCATION = "spp/probe/control/LiveInstrumentRemote"
        private const val REMOTE_CHECK_DESC = "(Ljava/lang/String;)Z"
        private const val REMOTE_SAVE_VAR_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V"
        private const val PUT_LOG_DESC = "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V"

        fun isXRETURN(opcode: Int): Boolean {
            return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
        }
    }

    private val methodUniqueName: String
    private val access: Int
    private val classMetadata: ClassMetadata
    private var currentBeginLabel: Label? = null
    private var inOriginalCode = true
    private var liveInstrument: LiveSpan? = null

    init {
        methodUniqueName = methodName + desc
        this.access = access
        this.classMetadata = classMetadata

        val qualifiedArgs = mutableListOf<String>()
        val descArgs = StringBuilder(desc.substringAfter("(").substringBefore(")"))
        while (descArgs.isNotEmpty()) {
            val primitive = getQualifiedPrimitive(descArgs[0])
            if (primitive != null) {
                qualifiedArgs.add(primitive)
                descArgs.deleteCharAt(0)
            } else if (descArgs[0] == 'L') {
                val end = descArgs.indexOf(";")
                qualifiedArgs.add(descArgs.substring(1, end).replace('/', '.'))
                descArgs.delete(0, end + 1)
            } else if (descArgs[0] == '[') {
                if (descArgs[1] == 'L') {
                    val end = descArgs.indexOf(";")
                    qualifiedArgs.add(descArgs.substring(2, end).replace('/', '.') + "[]")
                    descArgs.delete(0, end + 1)
                } else {
                    qualifiedArgs.add(getQualifiedPrimitive(descArgs[1])!! + "[]")
                    descArgs.delete(0, 2)
                }
            } else {
                throw IllegalArgumentException("Invalid descriptor: $desc")
            }
        }

        val qualifiedMethodName = "${className.replace("/", ".")}.$methodName(${qualifiedArgs.joinToString(",")})"
        val activeSpans = LiveInstrumentService.getInstruments(qualifiedMethodName)
        if (activeSpans.size == 1) {
            liveInstrument = activeSpans[0].instrument as LiveSpan
        } else if (activeSpans.size > 1) {
            TODO()
        }
    }

    override fun visitLineNumber(line: Int, start: Label) {
        mv.visitLineNumber(line, start)
        for (instrument in LiveInstrumentService.getInstruments(className.replace("/", "."), line)) {
            val instrumentLabel = Label()
            isInstrumentEnabled(instrument.instrument.id!!, instrumentLabel)
            when (instrument.instrument) {
                is LiveBreakpoint -> {
                    captureSnapshot(instrument.instrument.id!!, line)
                    isHit(instrument.instrument.id!!, instrumentLabel)
                    putBreakpoint(instrument.instrument.id!!, className.replace("/", "."), line)
                }
                is LiveLog -> {
                    val log = instrument.instrument
                    if (log.logArguments.isNotEmpty() || instrument.expression != null) {
                        captureSnapshot(log.id!!, line)
                    }
                    isHit(log.id!!, instrumentLabel)
                    putLog(log)
                }
                is LiveMeter -> {
                    val meter = instrument.instrument
                    if (instrument.expression != null) {
                        captureSnapshot(meter.id!!, line)
                    }
                    isHit(meter.id!!, instrumentLabel)
                    putMeter(meter)
                }
            }
            mv.visitLabel(Label())
            mv.visitLabel(instrumentLabel)
        }
    }

    private fun isInstrumentEnabled(instrumentId: String, instrumentLabel: Label) {
        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION, "isInstrumentEnabled",
            REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(Opcodes.IFEQ, instrumentLabel)
    }

    private fun captureSnapshot(instrumentId: String, line: Int) {
        addLocals(instrumentId, line)
        addStaticFields(instrumentId)
        addFields(instrumentId)
    }

    private fun isHit(instrumentId: String, instrumentLabel: Label) {
        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION, "isHit",
            REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(Opcodes.IFEQ, instrumentLabel)
    }

    private fun addLocals(instrumentId: String, line: Int) {
        for (local in classMetadata.variables[methodUniqueName].orEmpty()) {
            if (line >= local.start && line < local.end) {
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(local.name)
                mv.visitVarInsn(Type.getType(local.desc).getOpcode(Opcodes.ILOAD), local.index)
                LiveTransformer.boxIfNecessary(mv, local.desc)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
                    "putLocalVariable", REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun addStaticFields(instrumentId: String) {
        for (staticField in classMetadata.staticFields) {
            mv.visitLdcInsn(instrumentId)
            mv.visitLdcInsn(staticField.name)
            mv.visitFieldInsn(Opcodes.GETSTATIC, className, staticField.name, staticField.desc)
            LiveTransformer.boxIfNecessary(mv, staticField.desc)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
                "putStaticField", REMOTE_SAVE_VAR_DESC, false
            )
        }
    }

    private fun addFields(instrumentId: String) {
        if (access and Opcodes.ACC_STATIC == 0) {
            for (field in classMetadata.fields) {
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(field.name)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(Opcodes.GETFIELD, className, field.name, field.desc)
                LiveTransformer.boxIfNecessary(mv, field.desc)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
                    "putField", REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun putBreakpoint(instrumentId: String, source: String, line: Int) {
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

    private fun putLog(log: LiveLog) {
        mv.visitLdcInsn(log.id)
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

    private fun putMeter(meter: LiveMeter) {
        ProbeMemory.put("spp.live-meter:" + meter.id, meter)
        mv.visitLdcInsn(meter.id)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION, "putMeter", "(Ljava/lang/String;)V", false)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        mv.visitMaxs(maxStack.coerceAtLeast(4), maxLocals)
    }

    override fun visitCode() {
        if (liveInstrument is LiveSpan) {
            try {
                inOriginalCode = false
                execVisitBeforeFirstTryCatchBlock()
                beginTryBlock()
            } finally {
                inOriginalCode = true
            }
        } else {
            super.visitCode()
        }
    }

    override fun visitInsn(opcode: Int) {
        if (liveInstrument is LiveSpan) {
            if (inOriginalCode && isXRETURN(opcode)) {
                try {
                    inOriginalCode = false
                    completeTryFinallyBlock()

                    // visit the return instruction
                    visitInsn(opcode)

                    // begin the next try-block (it will not be added until it has been completed)
                    beginTryBlock()
                } finally {
                    inOriginalCode = true
                }
            } else if (inOriginalCode && opcode == Opcodes.ATHROW) {
                visitLdcInsn(liveInstrument!!.id)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION, "closeLocalSpanAndThrowException",
                    "(Ljava/lang/Throwable;Ljava/lang/String;)Ljava/lang/Throwable;", false
                )

                try {
                    inOriginalCode = false
                    visitInsn(opcode)
                } finally {
                    inOriginalCode = true
                }
            } else {
                super.visitInsn(opcode)
            }
        } else {
            super.visitInsn(opcode)
        }
    }

    private fun beginTryBlock() {
        currentBeginLabel = Label()
        visitLabel(currentBeginLabel)
    }

    private fun completeTryFinallyBlock() {
        val endLabel = Label()
        visitTryCatchBlock(currentBeginLabel, endLabel, endLabel, null)
        val l2 = Label()
        visitJumpInsn(Opcodes.GOTO, l2)
        visitLabel(endLabel)
        visitVarInsn(Opcodes.ASTORE, 1)
        execVisitFinallyBlock()
        visitVarInsn(Opcodes.ALOAD, 1)
        visitInsn(Opcodes.ATHROW)
        visitLabel(l2)
        execVisitFinallyBlock()
    }

    private fun execVisitBeforeFirstTryCatchBlock() {
        ProbeMemory.put("spp.live-span:" + liveInstrument!!.id, liveInstrument)
        visitLdcInsn(liveInstrument!!.id)
        visitMethodInsn(
            Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
            "openLocalSpan", "(Ljava/lang/String;)V", false
        )
    }

    private fun execVisitFinallyBlock() {
        visitLdcInsn(liveInstrument!!.id)
        visitMethodInsn(
            Opcodes.INVOKESTATIC, REMOTE_CLASS_LOCATION,
            "closeLocalSpan", "(Ljava/lang/String;)V", false
        )
    }

    private fun getQualifiedPrimitive(char: Char): String? {
        return when (char) {
            'Z' -> "boolean"
            'B' -> "byte"
            'C' -> "char"
            'S' -> "short"
            'I' -> "int"
            'F' -> "float"
            'J' -> "long"
            'D' -> "double"
            else -> null
        }
    }
}
