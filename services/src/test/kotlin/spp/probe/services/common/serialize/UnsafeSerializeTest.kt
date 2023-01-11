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
package spp.probe.services.common.serialize

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.probe.services.common.ModelSerializer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class UnsafeSerializeTest : AbstractSerializeTest {

    class SunServer : HttpHandler {
        var server: HttpServer? = null
        var exchange: HttpExchange? = null
        var httpPort: Int = 0

        fun init() {
            try {
                server = HttpServer.create(InetSocketAddress("0.0.0.0", httpPort), 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            server!!.createContext("/", this)
            server!!.executor = null
            server!!.start()
            httpPort = server!!.address.port
        }

        override fun handle(exchange: HttpExchange) {
            this.exchange = exchange
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
    }

    @Test
    fun serializeHttpExchange() {
        val sunServer = SunServer()
        sunServer.init()
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().GET()
                .uri(java.net.URI.create("http://localhost:${sunServer.httpPort}"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        val httpExchange = sunServer.exchange
        sunServer.server!!.stop(0)

        val rawJson = ModelSerializer.INSTANCE.toExtendedJson(httpExchange)
        val json = JsonObject(rawJson)
        assertEquals(3, json.size())

        val impl = json.getJsonObject("impl")
        assertEquals(30.0, impl.size().toDouble(), 2.0)

        //depth 3 (depth 1 = httpExchange, depth 2 = httpExchange.impl)
        val req = impl.getJsonObject("req")
        assertEquals(13, req.size())
        //depth 4
        val os = req.getJsonObject("os")
        assertEquals(9, os.size())
        //depth 5 (default max depth)
        val channel = os.getJsonObject("channel")
        assertEquals(25.0, channel.size().toDouble(), 1.0)

        //depth 6 (max depth exceeded)
        val fd = channel.getJsonObject("fd")
        assertEquals("MAX_DEPTH_EXCEEDED", fd.getString("@skip"))
        assertEquals("java.io.FileDescriptor", fd.getString("@class"))
        assertNotNull(fd.getString("@id"))
        assertNotNull(fd.getNumber("@size"))

        //ensure @ref works
        val serverRef = impl.getJsonObject("server")
        assertNotNull(serverRef.getString("@ref"))
        assertEquals("sun.net.httpserver.ServerImpl", serverRef.getString("@class"))

        //serialized depth first, so ref will be in req -> os -> server
        val server = req.getJsonObject("os").getJsonObject("server")
        assertEquals(41, server.size())
        assertEquals("sun.net.httpserver.ServerImpl", server.getString("@class"))
        assertEquals(serverRef.getString("@ref"), server.getString("@id"))

        //ensure max size works
        val buf = req.getJsonArray("buf")
        assertEquals(101, buf.size())
        val maxSizeError = buf.getJsonObject(100)
        assertEquals("MAX_LENGTH_EXCEEDED", maxSizeError.getString("@skip"))
        assertEquals(2048, maxSizeError.getInteger("@skip[size]"))
        assertEquals(100, maxSizeError.getInteger("@skip[max]"))
    }
}
