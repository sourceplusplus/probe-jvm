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
package spp.probe.services.common.transform

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.probe.ProbeConfiguration

class LiveTransformerTest {

    @Test
    fun metadataTest() {
        ProbeConfiguration.localProperties = JsonObject().put("spp", JsonObject())
        val transformer = LiveTransformer(LabelLines::class.java.name)
        transformer.transform(
            LabelLines::class.java.classLoader,
            LabelLines::class.java.name.replace('.', '/'),
            null,
            LabelLines::class.java.protectionDomain,
            LabelLines::class.java.classLoader.getResourceAsStream(
                LabelLines::class.java.name.replace('.', '/') + ".class"
            )!!.readBytes()
        )

        val metadata = transformer.classMetadata
        assertEquals(2, metadata.variables.size)

        val labelLinesVars = metadata.variables["labelLines()V"]!!
        assertEquals(4, labelLinesVars.size)

        val varZ = labelLinesVars[0]
        assertEquals("z", varZ.name)
        assertEquals("I", varZ.desc)
        assertEquals(2, varZ.startLabel)
        assertEquals(3, varZ.endLabel)
        assertEquals(20, varZ.line) //todo: fix

        val varI = labelLinesVars[1]
        assertEquals("i", varI.name)
        assertEquals("I", varI.desc)
        assertEquals(4, varI.startLabel)
        assertEquals(6, varI.endLabel)
        assertEquals(22, varI.line) //todo: fix

        val varB = labelLinesVars[2]
        assertEquals("b", varB.name)
        assertEquals("I", varB.desc)
        assertEquals(5, varB.startLabel)
        assertEquals(6, varB.endLabel)
        assertEquals(23, varB.line)
    }
}