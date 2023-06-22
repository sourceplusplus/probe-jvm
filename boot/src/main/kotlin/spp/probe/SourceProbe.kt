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
package spp.probe

import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import org.apache.skywalking.apm.agent.core.conf.Config
import org.apache.skywalking.apm.agent.core.logging.core.LogLevel
import spp.probe.ProbeConfiguration.PROBE_DIRECTORY
import spp.probe.ProbeConfiguration.PROBE_ID
import spp.probe.ProbeConfiguration.instrumentation
import spp.probe.ProbeConfiguration.probeMessageHeaders
import spp.probe.ProbeConfiguration.tcpSocket
import spp.probe.remotes.ILiveInstrumentRemote
import spp.probe.remotes.ILiveInstrumentRemote.Companion.INITIAL_INSTRUMENTS_SET
import spp.probe.util.NopInternalLogger
import spp.probe.util.NopLogDelegateFactory
import spp.protocol.artifact.ArtifactLanguage
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.extend.TCPServiceSocket
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
import kotlin.system.exitProcess

object SourceProbe {

    private val BUILD = ResourceBundle.getBundle("probe_build")

    @JvmStatic
    lateinit var vertx: Vertx
    private val connected = AtomicBoolean()

    @JvmStatic
    lateinit var instrumentRemote: ILiveInstrumentRemote

    val isAgentInitialized: Boolean
        get() = instrumentation != null

    @JvmStatic
    fun bootAsPlugin(inst: Instrumentation) {
        if (isAgentInitialized) {
            if (ProbeConfiguration.isNotQuiet) println("SourceProbe is already initialized")
            return
        }
        if (ProbeConfiguration.isNotQuiet) println("SourceProbe initiated via plugin")

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
        bootProbe()

        val connect = connectToPlatform()
        if (ProbeConfiguration.waitForPlatform) {
            connect.toCompletionStage().toCompletableFuture().get()
        }
    }

    @JvmStatic
    fun premain(args: String?, inst: Instrumentation) {
        if (System.getenv("SPP_PROBE_CONFIG_FILE") != null) {
            ProbeConfiguration.customProbeFile = System.getenv("SPP_PROBE_CONFIG_FILE")
        } else {
            ProbeConfiguration.customProbeFile = args
        }

        ProbeConfiguration.load()
        if (ProbeConfiguration.spp.getString("enabled") == "false") {
            if (ProbeConfiguration.isNotQuiet) println("SourceProbe is disabled")
            return
        }
        if (ProbeConfiguration.isNotQuiet) println("SourceProbe initiated via premain. args: $args")
        if (isAgentInitialized) {
            if (ProbeConfiguration.isNotQuiet) println("SourceProbe is already initialized")
            return
        }
        if (ProbeConfiguration.isNotQuiet) println("SourceProbe initiated via agent")

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
        bootProbe()

        val connect = connectToPlatform()
        if (ProbeConfiguration.waitForPlatform) {
            connect.toCompletionStage().toCompletableFuture().get()
        }
    }

    @Suppress("TooGenericExceptionThrown", "PrintStackTrace") // no logging framework available yet
    private fun bootProbe() {
        try {
            val agentClassLoader = Class.forName(
                "org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader"
            ).getMethod("getDefault").invoke(null) as java.lang.ClassLoader
            val instrumentRemoteClass = Class.forName(
                "spp.probe.services.LiveInstrumentRemote", true, agentClassLoader
            )
            instrumentRemote = instrumentRemoteClass.declaredConstructors.first().newInstance() as ILiveInstrumentRemote
            vertx.deployVerticle(instrumentRemote).onFailure {
                it.printStackTrace()
                exitProcess(-1)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    @Synchronized
    fun connectToPlatform(): Future<Void> {
        val promise = Promise.promise<Void>()
        if (connected.get()) {
            promise.complete()
            return promise.future()
        }

        val options = NetClientOptions()
            .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
            .setSsl(ProbeConfiguration.sslEnabled)
            .setTrustAll(!ProbeConfiguration.spp.getValue("verify_host", true).toString().toBooleanStrict())
            .apply {
                if (ProbeConfiguration.getString("platform_certificate")?.isNotBlank() == true) {
                    val myCaAsABuffer = Buffer.buffer(
                        "-----BEGIN CERTIFICATE-----" +
                                ProbeConfiguration.getString("platform_certificate") +
                                "-----END CERTIFICATE-----"
                    )
                    pemTrustOptions = PemTrustOptions().addCertValue(myCaAsABuffer)
                }
            }

        val client = vertx.createNetClient(options)
        client.connect(
            ProbeConfiguration.getInteger("platform_port"),
            ProbeConfiguration.getString("platform_host")
        ) { socket: AsyncResult<NetSocket> ->
            if (socket.failed()) {
                if (ProbeConfiguration.isNotQuiet) System.err.println("Failed to connect to Source++ Platform")
                if (ProbeConfiguration.isNotQuiet) socket.cause().printStackTrace()
                connectToPlatform()
                return@connect
            } else {
                tcpSocket = socket.result()
                connected.set(true)
            }

            if (ProbeConfiguration.isNotQuiet) println("Connected to Source++ Platform")
            TCPServiceSocket(vertx, socket.result()).exceptionHandler {
                connected.set(false)
                connectToPlatform()
            }.closeHandler {
                if (ProbeConfiguration.isNotQuiet) println("Disconnected from Source++ Platform")
                connected.set(false)
                vertx.setTimer(5000) {
                    connectToPlatform()
                }
            }

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
            val consumer = vertx.eventBus().localConsumer<Boolean>(replyAddress)
            consumer.handler {
                if (ProbeConfiguration.isNotQuiet) println("Received probe connection confirmation")

                vertx.eventBus().localConsumer<JsonObject>(INITIAL_INSTRUMENTS_SET).handler {
                    if (ProbeConfiguration.isNotQuiet) println("Initial instruments set")
                    promise.complete()
                }.completionHandler {
                    instrumentRemote.registerRemote()
                }

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

        return promise.future()
    }

    @Suppress("PrintStackTrace") // no logging framework available yet
    private fun invokeAgent() {
        if (ProbeConfiguration.isNotQuiet) println("SourceProbe finished setup")
        try {
            val skywalkingPremain = Class.forName("org.apache.skywalking.apm.agent.SkyWalkingAgent")
                .getMethod("premain", String::class.java, Instrumentation::class.java)
            skywalkingPremain.invoke(null, null, instrumentation)
        } catch (ex: Throwable) {
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
            ?: if (ProbeConfiguration.jvmMajorVersion >= 9) {
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
        val deleteProbeDirOnBoot = ProbeConfiguration.spp.getValue(
            "delete_probe_directory_on_boot", true
        ).toString().toBooleanStrict()
        if (deleteProbeDirOnBoot) {
            deleteRecursively(PROBE_DIRECTORY)
        }
        PROBE_DIRECTORY.mkdirs()
        ZipInputStream(
            Objects.requireNonNull(
                SourceProbe::class.java.classLoader.getResourceAsStream(
                    String.format(Locale.ENGLISH, "skywalking-agent-%s.zip", skywalkingVersion)
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
            if (ProbeConfiguration.getString("platform_certificate")?.isNotBlank() == true) {
                val myCaAsABuffer = Buffer.buffer(
                    "-----BEGIN CERTIFICATE-----\n" +
                            ProbeConfiguration.getString("platform_certificate").let {
                                val formatted = it!!.replace(" ", "")
                                    .chunked(64).joinToString("\n")
                                if (formatted.endsWith("\n")) formatted else formatted + "\n"
                            } + "-----END CERTIFICATE-----"
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
}
