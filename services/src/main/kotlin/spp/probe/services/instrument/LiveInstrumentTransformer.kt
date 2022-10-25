/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022 CodeBrig, Inc.
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
package spp.probe.services.instrument

import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import spp.probe.remotes.ILiveInstrumentRemote
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
        private val log = LogManager.getLogger(LiveInstrumentTransformer::class.java)
        private val THROWABLE_INTERNAL_NAME = Type.getInternalName(Throwable::class.java)
        private val INSTRUMENT_REMOTE_INTERNAL_NAME = Type.getDescriptor(ILiveInstrumentRemote::class.java)
        const val PROBE_CLASS_LOCATION = "spp/probe/SourceProbe"
        private const val REMOTE_CHECK_DESC = "(Ljava/lang/String;)Z"
        private const val REMOTE_SAVE_VAR_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)V"
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
                log.warn("Invalid descriptor: $desc")
                throw IllegalArgumentException("Invalid descriptor: $desc")
            }
        }

        val qualifiedMethodName = "${className.replace("/", ".")}.$methodName(${qualifiedArgs.joinToString(",")})"
        val activeSpans = LiveInstrumentService.getInstruments(qualifiedMethodName)
        if (activeSpans.size == 1) {
            liveInstrument = activeSpans[0].instrument as LiveSpan
        } else if (activeSpans.size > 1) {
            log.warn("Multiple live spans found for $qualifiedMethodName")
            TODO()
        }
    }

    override fun visitLineNumber(line: Int, start: Label) {
        mv.visitLineNumber(line, start)
        for (instrument in LiveInstrumentService.getInstruments(className.replace("/", "."), line)) {
            if (log.isInfoEnable) {
                log.info("Injecting live instrument {} on line {} of {}", instrument.instrument, line, className)
            }

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
        mv.visitFieldInsn(
            Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
            "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
        )

        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "spp/probe/remotes/ILiveInstrumentRemote",
            "isInstrumentEnabled",
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
        mv.visitFieldInsn(
            Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
            "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
        )

        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "spp/probe/remotes/ILiveInstrumentRemote",
            "isHit",
            REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(Opcodes.IFEQ, instrumentLabel)
    }

    private fun addLocals(instrumentId: String, line: Int) {
        for (local in classMetadata.variables[methodUniqueName].orEmpty()) {
            if (line >= local.start && line < local.end) {
                mv.visitFieldInsn(
                    Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
                    "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
                )

                val type = Type.getType(local.desc)
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(local.name)
                mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), local.index)
                LiveTransformer.boxIfNecessary(mv, local.desc)
                mv.visitLdcInsn(type.className)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "spp/probe/remotes/ILiveInstrumentRemote",
                    "putLocalVariable",
                    REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun addStaticFields(instrumentId: String) {
        for (staticField in classMetadata.staticFields) {
            mv.visitFieldInsn(
                Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
                "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
            )

            val type = Type.getType(staticField.desc)
            mv.visitLdcInsn(instrumentId)
            mv.visitLdcInsn(staticField.name)
            mv.visitFieldInsn(Opcodes.GETSTATIC, className, staticField.name, staticField.desc)
            LiveTransformer.boxIfNecessary(mv, staticField.desc)
            mv.visitLdcInsn(type.className)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "spp/probe/remotes/ILiveInstrumentRemote",
                "putStaticField",
                REMOTE_SAVE_VAR_DESC, false
            )
        }
    }

    private fun addFields(instrumentId: String) {
        if (access and Opcodes.ACC_STATIC == 0) {
            for (field in classMetadata.fields) {
                mv.visitFieldInsn(
                    Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
                    "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
                )

                val type = Type.getType(field.desc)
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(field.name)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(Opcodes.GETFIELD, className, field.name, field.desc)
                LiveTransformer.boxIfNecessary(mv, field.desc)
                mv.visitLdcInsn(type.className)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "spp/probe/remotes/ILiveInstrumentRemote",
                    "putField",
                    REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun putBreakpoint(instrumentId: String, source: String, line: Int) {
        mv.visitFieldInsn(
            Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
            "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
        )

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
            Opcodes.INVOKEVIRTUAL,
            "spp/probe/remotes/ILiveInstrumentRemote",
            "putBreakpoint",
            "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/Throwable;)V", false
        )
    }

    private fun putLog(log: LiveLog) {
        mv.visitFieldInsn(
            Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
            "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
        )

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
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "spp/probe/remotes/ILiveInstrumentRemote",
            "putLog",
            PUT_LOG_DESC, false
        )
    }

    private fun putMeter(meter: LiveMeter) {
        mv.visitFieldInsn(
            Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
            "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
        )

        ProbeMemory.put("spp.live-meter:" + meter.id, meter)
        mv.visitLdcInsn(meter.id)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "spp/probe/remotes/ILiveInstrumentRemote",
            "putMeter",
            "(Ljava/lang/String;)V", false
        )
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
                mv.visitFieldInsn(
                    Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
                    "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
                )

                visitLdcInsn(liveInstrument!!.id)
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "spp/probe/remotes/ILiveInstrumentRemote",
                    "closeLocalSpanAndThrowException",
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
        mv.visitFieldInsn(
            Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
            "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
        )

        ProbeMemory.put("spp.live-span:" + liveInstrument!!.id, liveInstrument)
        visitLdcInsn(liveInstrument!!.id)
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "spp/probe/remotes/ILiveInstrumentRemote",
            "openLocalSpan",
            "(Ljava/lang/String;)V", false
        )
    }

    private fun execVisitFinallyBlock() {
        mv.visitFieldInsn(
            Opcodes.GETSTATIC, PROBE_CLASS_LOCATION,
            "instrumentRemote", INSTRUMENT_REMOTE_INTERNAL_NAME
        )

        visitLdcInsn(liveInstrument!!.id)
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "spp/probe/remotes/ILiveInstrumentRemote",
            "closeLocalSpan",
            "(Ljava/lang/String;)V", false
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
