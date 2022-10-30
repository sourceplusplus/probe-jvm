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
import net.bytebuddy.jar.asm.Opcodes.*
import net.bytebuddy.jar.asm.Type.*
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import spp.probe.remotes.ILiveInstrumentRemote
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
    private val access: Int,
    private val classMetadata: ClassMetadata,
    mv: MethodVisitor
) : MethodVisitor(ASM7, mv) {

    companion object {
        private val log = LogManager.getLogger(LiveInstrumentTransformer::class.java)
        private const val PROBE_INTERNAL_NAME = "spp/probe/SourceProbe"
        private const val REMOTE_FIELD = "instrumentRemote"
        private val REMOTE_CLASS = ILiveInstrumentRemote::class.java
        private val REMOTE_DESCRIPTOR = getDescriptor(REMOTE_CLASS)
        private val REMOTE_INTERNAL_NAME = getInternalName(REMOTE_CLASS)
        private val THROWABLE_INTERNAL_NAME = getInternalName(Throwable::class.java)
        private val REMOTE_CHECK_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "isHit" })
        private val REMOTE_SAVE_VAR_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putField" })
        private val REMOTE_PUT_RETURN_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putReturn" })
        private val PUT_BREAKPOINT_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putBreakpoint" })
        private val PUT_LOG_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putLog" })
        private val PUT_METER_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putMeter" })
        private val OPEN_CLOSE_SPAN_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "openLocalSpan" })
        private val CLOSE_AND_THROW_DESC = getMethodDescriptor(
            REMOTE_CLASS.methods.find { it.name == "closeLocalSpanAndThrowException" }
        )
    }

    private val methodUniqueName = methodName + desc
    private var currentBeginLabel: Label? = null
    private var inOriginalCode = true
    private var liveInstrument: LiveSpan? = null
    private var line = 0

    init {
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
        this.line = line

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
        mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "isInstrumentEnabled", REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(IFEQ, instrumentLabel)
    }

    private fun captureSnapshot(instrumentId: String, line: Int) {
        addLocals(instrumentId, line)
        addStaticFields(instrumentId)
        addFields(instrumentId)
    }

    private fun isHit(instrumentId: String, instrumentLabel: Label) {
        mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "isHit", REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(IFEQ, instrumentLabel)
    }

    private fun addLocals(instrumentId: String, line: Int) {
        for (local in classMetadata.variables[methodUniqueName].orEmpty()) {
            if (line >= local.start && line < local.end) {
                mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

                val type = getType(local.desc)
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(local.name)
                mv.visitVarInsn(type.getOpcode(ILOAD), local.index)
                LiveTransformer.boxIfNecessary(mv, local.desc)
                mv.visitLdcInsn(type.className)
                mv.visitMethodInsn(
                    INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                    "putLocalVariable", REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun addStaticFields(instrumentId: String) {
        for (staticField in classMetadata.staticFields) {
            mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

            val type = getType(staticField.desc)
            mv.visitLdcInsn(instrumentId)
            mv.visitLdcInsn(staticField.name)
            mv.visitFieldInsn(GETSTATIC, className, staticField.name, staticField.desc)
            LiveTransformer.boxIfNecessary(mv, staticField.desc)
            mv.visitLdcInsn(type.className)
            mv.visitMethodInsn(
                INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                "putStaticField", REMOTE_SAVE_VAR_DESC, false
            )
        }
    }

    private fun addFields(instrumentId: String) {
        if (access and ACC_STATIC == 0) {
            for (field in classMetadata.fields) {
                mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

                val type = getType(field.desc)
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(field.name)
                mv.visitVarInsn(ALOAD, 0)
                mv.visitFieldInsn(GETFIELD, className, field.name, field.desc)
                LiveTransformer.boxIfNecessary(mv, field.desc)
                mv.visitLdcInsn(type.className)
                mv.visitMethodInsn(
                    INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                    "putField", REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun putBreakpoint(instrumentId: String, source: String, line: Int) {
        mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(instrumentId)
        mv.visitLdcInsn(source)
        mv.visitLdcInsn(line)
        mv.visitTypeInsn(NEW, THROWABLE_INTERNAL_NAME)
        mv.visitInsn(DUP)
        mv.visitMethodInsn(
            INVOKESPECIAL, THROWABLE_INTERNAL_NAME,
            "<init>", "()V", false
        )
        mv.visitMethodInsn(
            INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "putBreakpoint", PUT_BREAKPOINT_DESC, false
        )
    }

    private fun putLog(log: LiveLog) {
        mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(log.id)
        mv.visitLdcInsn(log.logFormat)
        mv.visitIntInsn(BIPUSH, log.logArguments.size)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/String")
        for (i in log.logArguments.indices) {
            mv.visitInsn(DUP)
            mv.visitIntInsn(BIPUSH, i)
            mv.visitLdcInsn(log.logArguments[i])
            mv.visitInsn(AASTORE)
        }
        mv.visitMethodInsn(
            INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "putLog", PUT_LOG_DESC, false
        )
    }

    private fun putMeter(meter: LiveMeter) {
        mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(meter.id)
        mv.visitMethodInsn(
            INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "putMeter", PUT_METER_DESC, false
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
        if (isXRETURN(opcode)) {
            //check for live instruments added after the return
            val instrumentsAfterReturn = LiveInstrumentService.getInstruments(className.replace("/", "."), line + 1)
            if (instrumentsAfterReturn.isNotEmpty()) {
                for (instrument in instrumentsAfterReturn) {
                    if (log.isInfoEnable) {
                        log.info("Injecting live instrument {} after return on line {} of {}", instrument.instrument, line, className)
                    }

                    val instrumentLabel = Label()
                    isInstrumentEnabled(instrument.instrument.id!!, instrumentLabel)

                    when (instrument.instrument) {
                        is LiveBreakpoint -> {
                            //copy return value to top of stack
                            mv.visitInsn(DUP)

                            captureSnapshot(instrument.instrument.id!!, line)

                            //use putReturn to capture return value
                            mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)
                            mv.visitInsn(SWAP)

                            val type = getType(Any::class.java) //todo: get return type
                            mv.visitLdcInsn(instrument.instrument.id!!)
                            mv.visitInsn(SWAP)
                            //LiveTransformer.boxIfNecessary(mv, staticField.desc)
                            mv.visitLdcInsn(type.className)
                            mv.visitMethodInsn(
                                INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                                "putReturn", REMOTE_PUT_RETURN_DESC, false
                            )

                            isHit(instrument.instrument.id!!, instrumentLabel)
                            putBreakpoint(instrument.instrument.id!!, className.replace("/", "."), line)
                        }
                    }

                    mv.visitLabel(Label())
                    mv.visitLabel(instrumentLabel)
                }
            }
        }

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
            } else if (inOriginalCode && opcode == ATHROW) {
                mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

                visitLdcInsn(liveInstrument!!.id)
                visitMethodInsn(
                    INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                    "closeLocalSpanAndThrowException", CLOSE_AND_THROW_DESC, false
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
        visitJumpInsn(GOTO, l2)
        visitLabel(endLabel)
        visitVarInsn(ASTORE, 1)
        execVisitFinallyBlock()
        visitVarInsn(ALOAD, 1)
        visitInsn(ATHROW)
        visitLabel(l2)
        execVisitFinallyBlock()
    }

    private fun execVisitBeforeFirstTryCatchBlock() {
        mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        visitLdcInsn(liveInstrument!!.id)
        visitMethodInsn(
            INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "openLocalSpan", OPEN_CLOSE_SPAN_DESC, false
        )
    }

    private fun execVisitFinallyBlock() {
        mv.visitFieldInsn(GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        visitLdcInsn(liveInstrument!!.id)
        visitMethodInsn(
            INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "closeLocalSpan", OPEN_CLOSE_SPAN_DESC, false
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

    private fun isXRETURN(opcode: Int): Boolean {
        return opcode >= IRETURN && opcode <= RETURN
    }
}
