package spp.probe

import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
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
import spp.probe.control.LiveInstrumentRemote
import spp.probe.util.NopInternalLogger
import spp.probe.util.NopLogDelegateFactory
import spp.protocol.platform.PlatformAddress
import spp.protocol.probe.ProbeAddress
import spp.protocol.probe.status.ProbeConnection
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

    private val BUILD = ResourceBundle.getBundle("build")
    private var PROBE_DIRECTORY = File(
        if (System.getProperty("os.name").lowercase(Locale.getDefault()).startsWith("mac"))
            "/tmp" else System.getProperty("java.io.tmpdir"), "spp-probe"
    )
    var instrumentation: Instrumentation? = null

    @JvmField
    var vertx: Vertx? = null
    private val connected = AtomicBoolean()

    @JvmField
    var tcpSocket: NetSocket? = null
    var instrumentRemote: LiveInstrumentRemote? = null

    @JvmField
    val PROBE_ID = UUID.randomUUID().toString()
    val isAgentInitialized: Boolean
        get() = instrumentation != null

    @JvmStatic
    fun bootAsPlugin(inst: Instrumentation) {
        if (ProbeConfiguration.isNotQuite) println("SourceProbe initiated")

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

        configureAgent()
        connectToPlatform()
        try {
            val agentClassLoader = Class.forName(
                "org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader"
            ).getMethod("getDefault").invoke(null) as java.lang.ClassLoader
            val sizeCappedClass = Class.forName(
                "spp.probe.services.common.serialize.SizeCappedTypeAdapterFactory", true, agentClassLoader
            )
            sizeCappedClass.getMethod("setInstrumentation", Instrumentation::class.java)
                .invoke(null, instrumentation)
            sizeCappedClass.getMethod("setMaxMemorySize", Long::class.javaPrimitiveType)
                .invoke(null, 1024L * 1024L) //1MB
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
        vertx!!.deployVerticle(LiveInstrumentRemote().also { instrumentRemote = it })
    }

    @JvmStatic
    fun premain(args: String?, inst: Instrumentation) {
        if (ProbeConfiguration.isNotQuite) println("SourceProbe initiated")

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
        addAgentToClassLoader()
        configureAgent()
        invokeAgent()
        connectToPlatform()
        try {
            val agentClassLoader = Class.forName(
                "org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader"
            ).getMethod("getDefault").invoke(null) as java.lang.ClassLoader
            val sizeCappedClass = Class.forName(
                "spp.probe.services.common.serialize.SizeCappedTypeAdapterFactory", true, agentClassLoader
            )
            sizeCappedClass.getMethod("setInstrumentation", Instrumentation::class.java)
                .invoke(null, instrumentation)
            sizeCappedClass.getMethod("setMaxMemorySize", Long::class.javaPrimitiveType)
                .invoke(null, 1024L * 1024L) //1MB
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
        vertx!!.deployVerticle(LiveInstrumentRemote().also { instrumentRemote = it })
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
        val options = if (System.getenv("SPP_DISABLE_TLS") == "true"
            || ProbeConfiguration.getString("platform_certificate") == null
        ) {
            NetClientOptions()
                .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
                .setSsl(false)
        } else {
            val myCaAsABuffer = Buffer.buffer(
                "-----BEGIN CERTIFICATE-----" +
                        ProbeConfiguration.getString("platform_certificate") +
                        "-----END CERTIFICATE-----"
            )
            NetClientOptions()
                .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
                .setSsl(true)
                .setPemTrustOptions(PemTrustOptions().addCertValue(myCaAsABuffer))
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
                connectToPlatform()
            }

            //handle platform messages
            val parser = FrameParser { parse: AsyncResult<JsonObject> ->
                val frame = parse.result()
                if ("message" == frame.getString("type")) {
                    if (frame.getString("replyAddress") != null) {
                        vertx!!.eventBus().request<Any?>(
                            "local." + frame.getString("address"),
                            frame.getJsonObject("body")
                        ).onComplete {
                            if (it.succeeded()) {
                                FrameHelper.sendFrame(
                                    BridgeEventType.SEND.name.lowercase(Locale.getDefault()),
                                    frame.getString("replyAddress"),
                                    JsonObject.mapFrom(it.result().body()),
                                    socket.result()
                                )
                            } else {
                                FrameHelper.sendFrame(
                                    BridgeEventType.SEND.name.lowercase(Locale.getDefault()),
                                    frame.getString("replyAddress"),
                                    JsonObject.mapFrom(it.cause()),
                                    socket.result()
                                )
                            }
                        }
                    } else {
                        vertx!!.eventBus().publish(
                            "local." + frame.getString("address"),
                            frame.getJsonObject("body")
                        )
                    }
                } else {
                    throw UnsupportedOperationException(frame.toString())
                }
            }
            socket.result().handler(parser)

            //define probe metadata
            val meta = HashMap<String, Any>()
            meta["language"] = "java"
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

            //send probe connected status
            val replyAddress = UUID.randomUUID().toString()
            val pc = ProbeConnection(PROBE_ID, System.currentTimeMillis(), meta)
            val consumer = vertx!!.eventBus().localConsumer<Boolean>("local.$replyAddress")
            consumer.handler {
                if (ProbeConfiguration.isNotQuite) println("Received probe connection confirmation")

                //register remotes
                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.lowercase(Locale.getDefault()),
                    ProbeAddress.LIVE_BREAKPOINT_REMOTE.address + ":" + PROBE_ID,
                    JsonObject(),
                    tcpSocket
                )
                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.lowercase(Locale.getDefault()),
                    ProbeAddress.LIVE_LOG_REMOTE.address + ":" + PROBE_ID,
                    JsonObject(),
                    tcpSocket
                )
                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.lowercase(Locale.getDefault()),
                    ProbeAddress.LIVE_METER_REMOTE.address + ":" + PROBE_ID,
                    JsonObject(),
                    tcpSocket
                )
                consumer.unregister()
            }
            FrameHelper.sendFrame(
                BridgeEventType.SEND.name.lowercase(Locale.getDefault()), PlatformAddress.PROBE_CONNECTED.address,
                replyAddress, JsonObject(), true, JsonObject.mapFrom(pc), socket.result()
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

    private fun configureAgent() {
        ProbeConfiguration.skywalkingSettings.forEach { System.setProperty(it[0], it[1]) }
        ProbeConfiguration.sppSettings.forEach { System.setProperty(it[0], it[1]) }

        //add probe id to instance properties
        try {
            val skywalkingConfig = Class.forName("org.apache.skywalking.apm.agent.core.conf.Config\$Agent")
            val instanceProperties = skywalkingConfig.getField("INSTANCE_PROPERTIES").get(null)
                    as MutableMap<String, String>
            instanceProperties["probe_id"] = PROBE_ID
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
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
        if (System.getenv("SPP_DELETE_PROBE_DIRECTORY_ON_BOOT") != "false") {
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
            var version = System.getProperty("java.version")
            if (version.startsWith("1.")) {
                version = version.substring(2, 3)
            } else {
                val dot = version.indexOf(".")
                if (dot != -1) {
                    version = version.substring(0, dot)
                }
            }
            return version.toInt()
        }
}
