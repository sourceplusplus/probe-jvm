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

import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameParser
import org.apache.skywalking.apm.agent.core.conf.Config
import org.apache.skywalking.apm.agent.core.logging.core.LogLevel
import spp.probe.ProbeConfiguration.PROBE_ID
import spp.probe.ProbeConfiguration.instrumentation
import spp.probe.ProbeConfiguration.probeMessageHeaders
import spp.probe.ProbeConfiguration.tcpSocket
import spp.probe.util.NopInternalLogger
import spp.probe.util.NopLogDelegateFactory
import spp.protocol.artifact.ArtifactLanguage
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.status.InstanceConnection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.instrument.Instrumentation
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

object SourceProbe {

    private val BUILD = ResourceBundle.getBundle("probe_build")
    private var PROBE_DIRECTORY = File(
        if (System.getProperty("os.name").lowercase().startsWith("mac"))
            "/tmp" else System.getProperty("java.io.tmpdir"), "spp-probe"
    )

    @JvmField
    var vertx: Vertx? = null
    private val connected = AtomicBoolean()

    var instrumentRemote: AbstractVerticle? = null

    val isAgentInitialized: Boolean
        get() = instrumentation != null

    @JvmStatic
    fun bootAsPlugin(inst: Instrumentation) {
        if (isAgentInitialized) {
            if (ProbeConfiguration.isNotQuite) println("SourceProbe is already initialized")
            return
        }
        if (ProbeConfiguration.isNotQuite) println("SourceProbe initiated via plugin")

        //todo: pipe data if in debug mode
        System.setProperty("vertx.logger-delegate-factory-class-name", NopLogDelegateFactory::class.java.canonicalName)
        InternalLoggerFactory.setDefaultFactory(object : InternalLoggerFactory() {
            private val nopInternalLogger = NopInternalLogger()
            override fun newInstance(name: String): InternalLogger {
                return nopInternalLogger
            }
        })
        instrumentation = inst
        vertx = Vertx.vertx()

        configureAgent(false)
        connectToPlatform()
        try {
            val agentClassLoader = Class.forName(
                "org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader"
            ).getMethod("getDefault").invoke(null) as java.lang.ClassLoader
            val sizeCappedClass = Class.forName(
                "spp.probe.services.common.serialize.CappedTypeAdapterFactory", true, agentClassLoader
            )
            sizeCappedClass.getMethod("setInstrumentation", Instrumentation::class.java)
                .invoke(null, instrumentation)
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
//        vertx!!.deployVerticle(LiveInstrumentRemote().also { instrumentRemote = it })
    }

    @JvmStatic
    fun premain(args: String?, inst: Instrumentation) {
        ProbeConfiguration.customProbeFile = args
        ProbeConfiguration.load()
        if (ProbeConfiguration.isNotQuite) println("SourceProbe initiated via premain. args: $args")
        if (isAgentInitialized) {
            if (ProbeConfiguration.isNotQuite) println("SourceProbe is already initialized")
            return
        }
        if (ProbeConfiguration.isNotQuite) println("SourceProbe initiated via agent")

        //todo: pipe data if in debug mode
        System.setProperty("vertx.logger-delegate-factory-class-name", NopLogDelegateFactory::class.java.canonicalName)
        InternalLoggerFactory.setDefaultFactory(object : InternalLoggerFactory() {
            private val nopInternalLogger = NopInternalLogger()
            override fun newInstance(name: String): InternalLogger {
                return nopInternalLogger
            }
        })
        instrumentation = inst
        vertx = Vertx.vertx()

        unzipAgent(BUILD.getString("apache_skywalking_version"))
        updateCaCertIfNecessary()
        addAgentToClassLoader()
        configureAgent(true)
        invokeAgent()
        connectToPlatform()
        try {
            val agentClassLoader = Class.forName(
                "org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader"
            ).getMethod("getDefault").invoke(null) as java.lang.ClassLoader
            val sizeCappedClass = Class.forName(
                "spp.probe.services.common.serialize.CappedTypeAdapterFactory", true, agentClassLoader
            )
            sizeCappedClass.getMethod("setInstrumentation", Instrumentation::class.java)
                .invoke(null, instrumentation)
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
//        vertx!!.deployVerticle(LiveInstrumentRemote().also { instrumentRemote = it })
    }

    @JvmStatic
    fun disconnectFromPlatform() {
        connected.set(false)
        tcpSocket!!.close()
        tcpSocket = null
        instrumentRemote!!.stop()
        instrumentRemote = null
    }

    @JvmStatic
    @Synchronized
    fun connectToPlatform() {
        if (connected.get()) return
        val options = NetClientOptions()
            .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
            .setSsl(ProbeConfiguration.sslEnabled)
            .setTrustAll(!ProbeConfiguration.spp.getBoolean("verify_host", true))
            .apply {
                if (ProbeConfiguration.getString("platform_certificate") != null) {
                    val myCaAsABuffer = Buffer.buffer(
                        "-----BEGIN CERTIFICATE-----" +
                                ProbeConfiguration.getString("platform_certificate") +
                                "-----END CERTIFICATE-----"
                    )
                    pemTrustOptions = PemTrustOptions().addCertValue(myCaAsABuffer)
                }
            }

        val client = vertx!!.createNetClient(options)
        client.connect(
            ProbeConfiguration.getInteger("platform_port"),
            ProbeConfiguration.getString("platform_host")
        ) { socket: AsyncResult<NetSocket> ->
            if (socket.failed()) {
                if (ProbeConfiguration.isNotQuite) System.err.println("Failed to connect to Source++ Platform")
                if (ProbeConfiguration.isNotQuite) socket.cause().printStackTrace()
                connectToPlatform()
                return@connect
            } else {
                tcpSocket = socket.result()
                connected.set(true)
            }
            if (ProbeConfiguration.isNotQuite) println("Connected to Source++ Platform")
            socket.result().exceptionHandler {
                connected.set(false)
                connectToPlatform()
            }
            socket.result().closeHandler {
                connected.set(false)
                vertx!!.setTimer(5000) {
                    connectToPlatform()
                }
            }

            //handle platform messages
            val parser = FrameParser { parse: AsyncResult<JsonObject> ->
                val frame = parse.result()
                if ("message" == frame.getString("type")) {
                    if (frame.getString("replyAddress") != null) {
                        vertx!!.eventBus().request<Any?>(
                            frame.getString("address"),
                            frame.getJsonObject("body")
                        ).onComplete {
                            if (it.succeeded()) {
                                FrameHelper.sendFrame(
                                    BridgeEventType.SEND.name.lowercase(),
                                    frame.getString("replyAddress"),
                                    null,
                                    probeMessageHeaders,
                                    true,
                                    JsonObject.mapFrom(it.result().body()),
                                    socket.result()
                                )
                            } else {
                                FrameHelper.sendFrame(
                                    BridgeEventType.SEND.name.lowercase(),
                                    frame.getString("replyAddress"),
                                    null,
                                    probeMessageHeaders,
                                    true,
                                    JsonObject.mapFrom(it.cause()),
                                    socket.result()
                                )
                            }
                        }
                    } else {
                        vertx!!.eventBus().publish(
                            frame.getString("address"),
                            frame.getValue("body")
                        )
                    }
                } else if ("err" == frame.getString("type")) {
                    val errorMessage = frame.getString("message")
                    if (ProbeConfiguration.isNotQuite) {
                        if (errorMessage == "blocked by bridgeEvent handler") {
                            System.err.println("Probe authentication failed")
                        } else {
                            System.err.println(frame.getString("message"))
                        }
                    }
                    disconnectFromPlatform()
                } else {
                    throw UnsupportedOperationException(frame.toString())
                }
            }
            socket.result().handler(parser)

            //define probe metadata
            val meta = HashMap<String, Any>()
            meta["language"] = ArtifactLanguage.JVM.name.lowercase()
            meta["probe_version"] = BUILD.getString("build_version")
            meta["java_version"] = System.getProperty("java.version")
            try {
                val skywalkingConfig = Class.forName("org.apache.skywalking.apm.agent.core.conf.Config\$Agent")
                meta["service"] = skywalkingConfig.getField("SERVICE_NAME")[null]
                meta["service_instance"] = skywalkingConfig.getField("INSTANCE_NAME")[null]
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
            if (ProbeConfiguration.spp.containsKey("probe_metadata")) {
                meta.putAll(ProbeConfiguration.spp.getJsonObject("probe_metadata").map)
            }

            //add probe auth headers
            ProbeConfiguration.getJsonObject("authentication")?.let {
                it.getString("client_id")?.let { probeMessageHeaders.put("client_id", it) }
                it.getString("client_secret")?.let { probeMessageHeaders.put("client_secret", it) }
                it.getString("tenant_id")?.let { probeMessageHeaders.put("tenant_id", it) }
            }

            //send probe connected status
            val replyAddress = UUID.randomUUID().toString()
            val pc = InstanceConnection(PROBE_ID, System.currentTimeMillis(), meta)
            val consumer = vertx!!.eventBus().localConsumer<Boolean>(replyAddress)
            consumer.handler {
                if (ProbeConfiguration.isNotQuite) println("Received probe connection confirmation")

                //register instrument remote
                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.lowercase(),
                    ProbeAddress.LIVE_INSTRUMENT_REMOTE + ":" + PROBE_ID,
                    null,
                    probeMessageHeaders,
                    false,
                    JsonObject(),
                    tcpSocket
                )
                consumer.unregister()
            }
            FrameHelper.sendFrame(
                BridgeEventType.SEND.name.lowercase(),
                PlatformAddress.PROBE_CONNECTED,
                replyAddress,
                probeMessageHeaders,
                true,
                JsonObject.mapFrom(pc),
                socket.result()
            )
        }
    }

    private fun invokeAgent() {
        if (ProbeConfiguration.isNotQuite) println("SourceProbe finished setup")
        try {
            val skywalkingPremain = Class.forName("org.apache.skywalking.apm.agent.SkyWalkingAgent")
                .getMethod("premain", String::class.java, Instrumentation::class.java)
            skywalkingPremain.invoke(null, null, instrumentation)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun configureAgent(configureSkyWalking: Boolean) {
        if (configureSkyWalking) ProbeConfiguration.skywalkingSettings.forEach { System.setProperty(it[0], it[1]) }
        ProbeConfiguration.sppSettings.forEach { System.setProperty(it[0], it[1]) }
    }

    private fun addAgentToClassLoader() {
        val skywalkingAgentFile = File(PROBE_DIRECTORY, "skywalking-agent.jar")
        val contextClassLoader = Thread.currentThread().contextClassLoader
        val dynamic = ClassLoader.findAncestor(contextClassLoader)
        dynamic?.add(skywalkingAgentFile.toURI().toURL())
            ?: if (jvmMajorVersion >= 9) {
                instrumentation!!.appendToSystemClassLoaderSearch(JarFile(skywalkingAgentFile))
            } else {
                val classLoader = java.lang.ClassLoader.getSystemClassLoader() as URLClassLoader
                val method = URLClassLoader::class.java.getDeclaredMethod("addURL", URL::class.java)
                method.isAccessible = true
                method.invoke(classLoader, skywalkingAgentFile.toURI().toURL())
            }

        //org.apache.skywalking.+ must be referenced as fully qualified
        Config.Logging.LEVEL = LogLevel.valueOf(ProbeConfiguration.skyWalkingLoggingLevel)
    }

    private fun unzipAgent(skywalkingVersion: String) {
        val deleteProbeDirOnBoot = ProbeConfiguration.spp.getBoolean(
            "delete_probe_directory_on_boot",
            System.getenv("SPP_DELETE_PROBE_DIRECTORY_ON_BOOT")?.lowercase()?.toBooleanStrictOrNull()
        ) ?: true
        if (deleteProbeDirOnBoot) {
            deleteRecursively(PROBE_DIRECTORY)
        }
        PROBE_DIRECTORY.mkdirs()
        ZipInputStream(
            Objects.requireNonNull(
                SourceProbe::class.java.classLoader.getResourceAsStream(
                    String.format(
                        "skywalking-agent-%s.zip",
                        skywalkingVersion
                    )
                )
            )
        ).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                val newFile = File(PROBE_DIRECTORY, zipEntry.name)
                if (zipEntry.isDirectory) {
                    if (!newFile.isDirectory && !newFile.mkdirs()) {
                        throw IOException("Failed to create directory $newFile")
                    }
                } else {
                    val parent = newFile.parentFile
                    if (!parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("Failed to create directory $parent")
                    }
                    val buffer = ByteArray(1024)
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
        }
    }

    private fun updateCaCertIfNecessary() {
        val caCertFile = File(PROBE_DIRECTORY, "ca" + File.separator + "ca.crt")
        if (ProbeConfiguration.sslEnabled && !caCertFile.exists()) {
            if (ProbeConfiguration.getString("platform_certificate") != null) {
                val myCaAsABuffer = Buffer.buffer(
                    "-----BEGIN CERTIFICATE-----\n" +
                            ProbeConfiguration.getString("platform_certificate") +
                            "-----END CERTIFICATE-----"
                )

                caCertFile.parentFile.mkdirs()
                if (caCertFile.createNewFile()) {
                    caCertFile.writeBytes(myCaAsABuffer.bytes)
                } else {
                    throw IOException("Failed to create file $caCertFile")
                }
            }
        } else if (!ProbeConfiguration.sslEnabled && caCertFile.exists()) {
            caCertFile.delete()
        }
    }

    private fun deleteRecursively(directory: File) {
        val allContents = directory.listFiles()
        if (allContents != null) {
            for (file in allContents) {
                deleteRecursively(file)
            }
        }
        directory.delete()
    }

    private val jvmMajorVersion: Int
        get() {
            var version = System.getProperty("java.version").substringBefore("-")
            if (version.startsWith("1.")) {
                version = version.substring(2, 3)
            } else {
                version = version.substringBefore(".")
            }
            return version.toInt()
        }
}
