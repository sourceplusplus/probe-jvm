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
