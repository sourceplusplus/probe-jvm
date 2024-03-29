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

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import spp.probe.ProbeConfiguration
import java.lang.instrument.Instrumentation

interface AbstractSerializeTest {

    @BeforeEach
    fun setup() {
        ProbeConfiguration.localProperties = JsonObject().put("spp", JsonObject())
        ProbeConfiguration.variableControlByName.clear()
        ProbeConfiguration.variableControlByType.clear()
        ProbeConfiguration.variableControl.put("max_object_depth", 10)
        ProbeConfiguration.variableControl.put("max_object_depth", 5)
        ProbeConfiguration.variableControl.put("max_object_size", 1024L * 1024L) //1MB
        ProbeConfiguration.variableControl.put("max_collection_length", 100)
        ProbeConfiguration.instrumentation = Mockito.mock(Instrumentation::class.java).apply {
            Mockito.`when`(this.getObjectSize(Mockito.any())).thenReturn(0)
        }
    }
}
