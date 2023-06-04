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
package spp.probe.services.instrument

import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type.*
import org.apache.skywalking.apm.agent.core.logging.api.LogManager
import spp.probe.remotes.ILiveInstrumentRemote
import spp.probe.services.common.model.ActiveLiveInstrument
import spp.probe.services.common.model.ClassMetadata
import spp.probe.services.common.model.LocalVariable
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.LiveSpan
import spp.protocol.instrument.location.LocationScope
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MeterValueType
import spp.protocol.instrument.meter.MetricValueType

class LiveInstrumentTransformer(
    private val className: String,
    private val methodName: String,
    private val desc: String,
    private val access: Int,
    private val classMetadata: ClassMetadata,
    mv: MethodVisitor
) : MethodVisitor(Opcodes.ASM7, mv) {

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
        private val REMOTE_PUT_CONTEXT_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putContext" })
        private val PUT_BREAKPOINT_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putBreakpoint" })
        private val PUT_LOG_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putLog" })
        private val PUT_METER_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "putMeter" })
        private val START_STOP_TIMER_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "startTimer" })
        private val OPEN_CLOSE_SPAN_DESC = getMethodDescriptor(REMOTE_CLASS.methods.find { it.name == "openLocalSpan" })
        private val CLOSE_AND_THROW_DESC = getMethodDescriptor(
            REMOTE_CLASS.methods.find { it.name == "closeLocalSpanAndThrowException" }
        )
    }

    private val methodUniqueName = methodName + desc
    private var currentBeginLabel: NewLabel? = null
    private var inOriginalCode = true
    private val methodActiveInstruments = mutableListOf<ActiveLiveInstrument>()
    private var line = 0
    private lateinit var currentLabel: Label
    private val labelRanges = mutableMapOf<Label, Int>()

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

        val qualifiedClassName = className.replace('/', '.')
        val qualifiedMethodName = "$qualifiedClassName.$methodName(${qualifiedArgs.joinToString(",")})"

        //only apply method instruments to non-synthetic methods
        if (access and Opcodes.ACC_SYNTHETIC == 0) {
            methodActiveInstruments += LiveInstrumentService.getInstruments(qualifiedMethodName)
        }
    }

    override fun visitLabel(label: Label) {
        currentLabel = label
        if (label !is NewLabel) {
            labelRanges.putIfAbsent(label, labelRanges.size)
        }
        mv.visitLabel(label)
    }

    override fun visitLineNumber(line: Int, start: Label) {
        this.line = line

        mv.visitLineNumber(line, start)
        val concrete = classMetadata.concreteClass || methodName == "invokeSuspend" //todo: improve
        val lineInstruments = LiveInstrumentService.getInstruments(
            className.replace("/", ".").substringBefore("\$"), line
        ).filter {
            //filter line instruments outside current scope
            val scope = it.instrument.location.scope
            if (scope != LocationScope.BOTH) {
                (concrete && scope == LocationScope.LINE) || (!concrete && scope == LocationScope.LAMBDA)
            } else true
        }

        //apply line instruments
        for (instrument in lineInstruments) {
            if (log.isInfoEnable) {
                log.info("Injecting live instrument {} on line {} of {}", instrument.instrument.id, line, className)
            }

            val instrumentLabel = NewLabel()
            isInstrumentEnabled(instrument.instrument.id!!, instrumentLabel)
            when (instrument.instrument) {
                is LiveBreakpoint -> {
                    captureSnapshot(instrument.instrument.id!!)
                    isHit(instrument.instrument.id!!, instrumentLabel)
                    putBreakpoint(instrument.instrument.id!!)
                    instrument.sendAppliedEvent.set(true)
                }

                is LiveLog -> {
                    captureSnapshot(instrument.instrument.id!!)
                    isHit(instrument.instrument.id!!, instrumentLabel)
                    putLog(instrument.instrument)
                    instrument.sendAppliedEvent.set(true)
                }

                is LiveMeter -> {
                    val meter = instrument.instrument
                    if (instrument.expression != null || meter.metricValue?.valueType?.isExpression() == true) {
                        captureSnapshot(meter.id!!)
                    } else if (meter.meterTags.any { it.valueType == MeterValueType.VALUE_EXPRESSION }) {
                        captureSnapshot(meter.id!!)
                    } else if (meter.meterPartitions.any { it.valueType == MeterValueType.VALUE_EXPRESSION }) {
                        captureSnapshot(meter.id!!)
                    }
                    isHit(meter.id!!, instrumentLabel)
                    putMeter(meter)
                    instrument.sendAppliedEvent.set(true)
                }

                is LiveSpan -> Unit //handled via methodActiveInstruments
            }
            mv.visitLabel(NewLabel())
            mv.visitLabel(instrumentLabel)
        }
    }

    private fun startTimer(meterId: String) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(meterId)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "startTimer", START_STOP_TIMER_DESC, false
        )
    }

    private fun stopTimer(meterId: String) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(meterId)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "stopTimer", START_STOP_TIMER_DESC, false
        )
    }

    private fun isInstrumentEnabled(instrumentId: String, instrumentLabel: NewLabel) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "isInstrumentEnabled", REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(Opcodes.IFEQ, instrumentLabel)
    }

    private fun captureSnapshot(instrumentId: String) {
        addContext(instrumentId)
        addLocals(instrumentId)
        addStaticFields(instrumentId)
        addFields(instrumentId)
    }

    private fun isHit(instrumentId: String, instrumentLabel: NewLabel) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(instrumentId)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "isHit", REMOTE_CHECK_DESC, false
        )
        mv.visitJumpInsn(Opcodes.IFEQ, instrumentLabel)
    }

    private fun addContext(instrumentId: String) {
        val contextVars = mutableMapOf<String, Any>()
        contextVars["className"] = className.replace("/", ".")
        contextVars["methodName"] = methodName
        contextVars["methodDesc"] = desc
        contextVars["lineNumber"] = line

        contextVars.forEach {
            mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

            mv.visitLdcInsn(instrumentId)
            mv.visitLdcInsn(it.key)
            mv.visitLdcInsn(it.value)
            boxIfNecessary(mv, getType(it.value::class.javaPrimitiveType ?: it.value::class.java).descriptor)

            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                "putContext", REMOTE_PUT_CONTEXT_DESC, false
            )
        }
    }

    private fun addLocals(instrumentId: String) {
        val methodLocalVariables = classMetadata.variables[methodUniqueName]
        for (local in methodLocalVariables.orEmpty()) {
            if (isInLabelRange(methodLocalVariables!!, local)) {
                mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

                val type = getType(local.desc)
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(local.name)
                mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), local.index)
                boxIfNecessary(mv, local.desc)
                mv.visitLdcInsn(type.className)
                mv.visitLdcInsn(local.line)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                    "putLocalVariable", REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun isInLabelRange(methodLocalVariables: MutableList<LocalVariable>, local: LocalVariable): Boolean {
        //deny all local variables in <init> (todo: probably too restrictive)
        if (methodName == "<init>") return local.name == "this"

        //startLabel == 0 indicates that the variable is a parameter (maybe?)
        if (local.startLabel == 0) return true

        //local.startLabel/endLabel = this.startLabel/endLabel means the variable is a parameter (maybe?)
        if (methodLocalVariables.any { it.name == "this" }) {
            val thisVariable = methodLocalVariables.first { it.name == "this" }
            if (thisVariable.startLabel == local.startLabel && thisVariable.endLabel == local.endLabel) return true
        }

        if (::currentLabel.isInitialized.not()) return false
        val labelRange = labelRanges[currentLabel] ?: return false
        return labelRange >= local.startLabel && labelRange < local.endLabel
    }

    private fun addStaticFields(instrumentId: String) {
        for (staticField in classMetadata.staticFields) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

            val type = getType(staticField.desc)
            mv.visitLdcInsn(instrumentId)
            mv.visitLdcInsn(staticField.name)
            mv.visitFieldInsn(Opcodes.GETSTATIC, className, staticField.name, staticField.desc)
            boxIfNecessary(mv, staticField.desc)
            mv.visitLdcInsn(type.className)
            mv.visitLdcInsn(-1) //todo: this
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                "putStaticField", REMOTE_SAVE_VAR_DESC, false
            )
        }
    }

    private fun addFields(instrumentId: String) {
        if (access and Opcodes.ACC_STATIC == 0) {
            for (field in classMetadata.fields) {
                mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

                val type = getType(field.desc)
                mv.visitLdcInsn(instrumentId)
                mv.visitLdcInsn(field.name)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(Opcodes.GETFIELD, className, field.name, field.desc)
                boxIfNecessary(mv, field.desc)
                mv.visitLdcInsn(type.className)
                mv.visitLdcInsn(-1) //todo: this
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                    "putField", REMOTE_SAVE_VAR_DESC, false
                )
            }
        }
    }

    private fun putBreakpoint(instrumentId: String) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(instrumentId)
        mv.visitTypeInsn(Opcodes.NEW, THROWABLE_INTERNAL_NAME)
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, THROWABLE_INTERNAL_NAME,
            "<init>", "()V", false
        )
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "putBreakpoint", PUT_BREAKPOINT_DESC, false
        )
    }

    private fun putLog(log: LiveLog) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

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
            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "putLog", PUT_LOG_DESC, false
        )
    }

    private fun putMeter(meter: LiveMeter) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

        mv.visitLdcInsn(meter.id)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
            "putMeter", PUT_METER_DESC, false
        )
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        mv.visitMaxs(maxStack.coerceAtLeast(4), maxLocals)
    }

    override fun visitCode() {
        if (getTryCatchMethodInstruments().isNotEmpty()) {
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
            val instrumentsAfterReturn = getAfterReturnInstruments()
            if (instrumentsAfterReturn.isNotEmpty()) {
                for (instrument in instrumentsAfterReturn) {
                    if (log.isInfoEnable) {
                        log.info(
                            "Injecting live instrument {} after return on line {} of {}",
                            instrument.instrument.id, line, className
                        )
                    }

                    putInstrumentAfterReturn(instrument, opcode != Opcodes.RETURN)
                    instrument.sendAppliedEvent.set(true)
                }
            }
        }

        if (methodName != "<init>" && getTryCatchMethodInstruments().isNotEmpty()) {
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
                methodActiveInstruments.mapNotNull { it.instrument as? LiveSpan }.forEach {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

                    visitLdcInsn(it.id)
                    visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                        "closeLocalSpanAndThrowException", CLOSE_AND_THROW_DESC, false
                    )
                }

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

    private fun putInstrumentAfterReturn(instrument: ActiveLiveInstrument, hasValueReturn: Boolean) {
        val instrumentLabel = NewLabel()
        isInstrumentEnabled(instrument.instrument.id!!, instrumentLabel)

        when (instrument.instrument) {
            is LiveBreakpoint -> {
                //copy return value to top of stack
                if (hasValueReturn) {
                    mv.visitInsn(Opcodes.DUP)
                }

                captureSnapshot(instrument.instrument.id!!)

                //use putReturn to capture return value
                if (hasValueReturn) {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)
                    mv.visitInsn(Opcodes.SWAP)

                    val type = getMethodType(methodUniqueName).returnType
                    mv.visitLdcInsn(instrument.instrument.id!!)
                    mv.visitInsn(Opcodes.SWAP)
                    boxIfNecessary(mv, type.descriptor)
                    mv.visitLdcInsn(type.className)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                        "putReturn", REMOTE_PUT_RETURN_DESC, false
                    )
                }

                isHit(instrument.instrument.id!!, instrumentLabel)
                putBreakpoint(instrument.instrument.id!!)
            }

            is LiveLog -> {
                val log = instrument.instrument
                if (log.logArguments.isNotEmpty() || instrument.expression != null) {
                    //copy return value to top of stack
                    if (hasValueReturn) {
                        mv.visitInsn(Opcodes.DUP)
                    }

                    captureSnapshot(instrument.instrument.id!!)

                    //use putReturn to capture return value
                    if (hasValueReturn) {
                        mv.visitFieldInsn(
                            Opcodes.GETSTATIC,
                            PROBE_INTERNAL_NAME,
                            REMOTE_FIELD,
                            REMOTE_DESCRIPTOR
                        )
                        mv.visitInsn(Opcodes.SWAP)

                        val type = getMethodType(methodUniqueName).returnType
                        mv.visitLdcInsn(instrument.instrument.id!!)
                        mv.visitInsn(Opcodes.SWAP)
                        boxIfNecessary(mv, type.descriptor)
                        mv.visitLdcInsn(type.className)
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                            "putReturn", REMOTE_PUT_RETURN_DESC, false
                        )
                    }
                }
                isHit(log.id!!, instrumentLabel)
                putLog(log)
            }

            is LiveMeter -> {
                val meter = instrument.instrument
                if (shouldCaptureSnapshot(instrument)) {
                    //copy return value to top of stack
                    if (hasValueReturn) {
                        mv.visitInsn(Opcodes.DUP)
                    }

                    captureSnapshot(instrument.instrument.id!!)

                    //use putReturn to capture return value
                    if (hasValueReturn) {
                        mv.visitFieldInsn(
                            Opcodes.GETSTATIC,
                            PROBE_INTERNAL_NAME,
                            REMOTE_FIELD,
                            REMOTE_DESCRIPTOR
                        )
                        mv.visitInsn(Opcodes.SWAP)

                        val type = getMethodType(methodUniqueName).returnType
                        mv.visitLdcInsn(instrument.instrument.id!!)
                        mv.visitInsn(Opcodes.SWAP)
                        boxIfNecessary(mv, type.descriptor)
                        mv.visitLdcInsn(type.className)
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                            "putReturn", REMOTE_PUT_RETURN_DESC, false
                        )
                    }
                }
                isHit(meter.id!!, instrumentLabel)
                putMeter(meter)
            }

            is LiveSpan -> Unit //handled via methodActiveInstruments
        }

        mv.visitLabel(NewLabel())
        mv.visitLabel(instrumentLabel)
    }

    private fun beginTryBlock() {
        currentBeginLabel = NewLabel()
        visitLabel(currentBeginLabel!!)
    }

    private fun completeTryFinallyBlock() {
        val endLabel = NewLabel()
        visitTryCatchBlock(currentBeginLabel, endLabel, endLabel, null)
        val l2 = NewLabel()
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
        methodActiveInstruments.filter { it.instrument is LiveSpan }.forEach {
            mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

            visitLdcInsn(it.instrument.id)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                "openLocalSpan", OPEN_CLOSE_SPAN_DESC, false
            )
            it.sendAppliedEvent.set(true)
        }

        methodActiveInstruments.filter { (it.instrument as? LiveMeter)?.meterType == MeterType.METHOD_TIMER }.forEach {
            startTimer(it.instrument.id!!)
            it.sendAppliedEvent.set(true)
        }
    }

    private fun execVisitFinallyBlock() {
        methodActiveInstruments.mapNotNull { it.instrument as? LiveSpan }.forEach {
            mv.visitFieldInsn(Opcodes.GETSTATIC, PROBE_INTERNAL_NAME, REMOTE_FIELD, REMOTE_DESCRIPTOR)

            visitLdcInsn(it.id)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, REMOTE_INTERNAL_NAME,
                "closeLocalSpan", OPEN_CLOSE_SPAN_DESC, false
            )
        }

        methodActiveInstruments.mapNotNull { it.instrument as? LiveMeter }
            .filter { it.meterType == MeterType.METHOD_TIMER }
            .forEach {
                stopTimer(it.id!!)
            }
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
        return opcode in Opcodes.IRETURN..Opcodes.RETURN
    }

    private fun boxIfNecessary(mv: MethodVisitor, desc: String) {
        when (getType(desc).sort) {
            BOOLEAN -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Boolean",
                "valueOf",
                "(Z)Ljava/lang/Boolean;",
                false
            )

            BYTE -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Byte",
                "valueOf",
                "(B)Ljava/lang/Byte;",
                false
            )

            CHAR -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Character",
                "valueOf",
                "(C)Ljava/lang/Character;",
                false
            )

            DOUBLE -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Double",
                "valueOf",
                "(D)Ljava/lang/Double;",
                false
            )

            FLOAT -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Float",
                "valueOf",
                "(F)Ljava/lang/Float;",
                false
            )

            INT -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Integer",
                "valueOf",
                "(I)Ljava/lang/Integer;",
                false
            )

            LONG -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Long",
                "valueOf",
                "(J)Ljava/lang/Long;",
                false
            )

            SHORT -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Short",
                "valueOf",
                "(S)Ljava/lang/Short;",
                false
            )
        }
    }

    private fun getTryCatchMethodInstruments(): List<ActiveLiveInstrument> {
        return methodActiveInstruments.filter {
            it.instrument is LiveSpan || (it.instrument as? LiveMeter)?.meterType == MeterType.METHOD_TIMER
        }
    }

    private fun getAfterReturnInstruments(): List<ActiveLiveInstrument> {
        return methodActiveInstruments.filter {
            (it.instrument as? LiveMeter)?.meterType != MeterType.METHOD_TIMER
        } + LiveInstrumentService.getInstruments(className.replace("/", "."), line + 1)
    }

    private fun shouldCaptureSnapshot(instrument: ActiveLiveInstrument): Boolean {
        if (instrument.expression != null) return true
        return if (instrument.instrument is LiveMeter) {
            return shouldCaptureSnapshot(instrument.instrument)
        } else false
    }

    private fun shouldCaptureSnapshot(meter: LiveMeter): Boolean {
        return if (meter.metricValue?.valueType?.isExpression() == true) {
            true
        } else if (meter.meterTags.any { it.valueType == MeterValueType.VALUE_EXPRESSION }) {
            true
        } else if (meter.meterPartitions.any { it.valueType == MeterValueType.VALUE_EXPRESSION }) {
            true
        } else meter.metricValue?.valueType == MetricValueType.OBJECT_LIFESPAN
    }

    class NewLabel : Label()
}
