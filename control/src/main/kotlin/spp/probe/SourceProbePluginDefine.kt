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
package spp.probe

import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassInstanceMethodsEnhancePluginDefine
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Borrows Instrumentation from SkyWalking/Byte Buddy and boots SourceProbe.
 */
class SourceProbePluginDefine : ClassInstanceMethodsEnhancePluginDefine() {

    companion object {
        private val probeStarted = AtomicBoolean()
        private val cache = HashMap<String, ClassMatch>()
    }

    override fun enhanceClass(): ClassMatch {
        if (SourceProbe.isAgentInitialized) {
            if (ProbeConfiguration.isNotQuite) println("SourceProbe is already initialized")
            return NameMatch.byName("") //ignore
        }

        return cache.computeIfAbsent("cache") {
            val defaultClass = Class.forName(
                "org.apache.skywalking.apm.dependencies.net.bytebuddy.agent.builder.AgentBuilder\$Default"
            )
            val dispatcherField = defaultClass.getDeclaredField("DISPATCHER")
            makeAccessible(dispatcherField)
            val realDispatcher = dispatcherField.get(null)
            dispatcherField.set(null, null)

            val dispatcherClass = Class.forName(
                "org.apache.skywalking.apm.dependencies.net.bytebuddy.agent.builder.AgentBuilder\$Default\$Dispatcher"
            )
            val addTransformerMethod = dispatcherClass.getDeclaredMethod(
                "addTransformer",
                Instrumentation::class.java, ClassFileTransformer::class.java, Boolean::class.java
            )
            val setNativeMethodPrefixMethod = dispatcherClass.getDeclaredMethod(
                "setNativeMethodPrefix",
                Instrumentation::class.java, ClassFileTransformer::class.java, String::class.java
            )
            val isNativeMethodPrefixSupportedMethod = dispatcherClass.getDeclaredMethod(
                "isNativeMethodPrefixSupported", Instrumentation::class.java
            )

            val proxyDispatcher = Proxy.newProxyInstance(
                dispatcherClass.classLoader,
                arrayOf(dispatcherClass)
            ) { _, method, args ->
                return@newProxyInstance when (method.name) {
                    "addTransformer" -> {
                        if (probeStarted.compareAndSet(false, true)) {
                            SourceProbe.bootAsPlugin(args?.get(0) as Instrumentation)
                        }
                        addTransformerMethod.invoke(realDispatcher, args[0], args[1], args[2])
                    }
                    "setNativeMethodPrefix" -> {
                        setNativeMethodPrefixMethod.invoke(realDispatcher, args[0], args[1], args[2])
                    }
                    "isNativeMethodPrefixSupported" -> {
                        isNativeMethodPrefixSupportedMethod.invoke(realDispatcher, args[0])
                    }
                    else -> throw IllegalStateException("Unknown method: ${method.name}")
                }
            }
            dispatcherField.set(null, proxyDispatcher)

            return@computeIfAbsent NameMatch.byName("") //ignore
        }
    }

    @Throws(Exception::class)
    fun makeAccessible(field: Field) {
        field.isAccessible = true
        val modifiersField: Field = Field::class.java.getDeclaredField("modifiers")
        modifiersField.isAccessible = true
        modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
    }

    override fun getConstructorsInterceptPoints(): Array<ConstructorInterceptPoint> {
        return emptyArray() //ignore
    }

    override fun getInstanceMethodsInterceptPoints(): Array<InstanceMethodsInterceptPoint> {
        return emptyArray() //ignore
    }
}
