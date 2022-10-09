/*
 * Source++, the open-source live coding platform.
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
package spp.probe.services.common.serialize

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.vertx.core.json.JsonObject
import junit.framework.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import spp.probe.services.common.ModelSerializer
import java.lang.instrument.Instrumentation
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class UnsafeSerializeTest {

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
        CappedTypeAdapterFactory.setInstrumentation(Mockito.mock(Instrumentation::class.java))
        CappedTypeAdapterFactory.setMaxMemorySize(1024)

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
        assertEquals(29, json.size())

        //todo: add more assertions
    }
}
