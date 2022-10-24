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
package spp.probe.services.common.serialize

import io.vertx.core.json.JsonObject
import org.junit.Ignore
import org.junit.Test
import spp.probe.services.common.ModelSerializer

/**
 * Since serialization is done depth first, objects less than max depth can be references
 * to objects greater than max depth. These test(s) ensure objects over max depth serialize fully
 * with respect to the top level object's depth.
 */
@Ignore
class MaxDepthReferenceTest : AbstractSerializeTest {

    @Test
    fun `ensure references under max depth serialize`() {
        val deepObject = DeepObject1()
        val json = JsonObject(ModelSerializer.INSTANCE.toExtendedJson(deepObject))
        //todo: should be able to access FinalObject.i
    }

    val deepObject5 = DeepObject5()

    inner class DeepObject1 {
        val deepObject2 = DeepObject2()
        val deepObject5 = this@MaxDepthReferenceTest.deepObject5
    }

    inner class DeepObject2 {
        val deepObject3 = DeepObject3()
    }

    inner class DeepObject3 {
        val deepObject4 = DeepObject4()
    }

    inner class DeepObject4 {
        val deepObject5 = this@MaxDepthReferenceTest.deepObject5
    }

    inner class DeepObject5 {
        val finalObject = FinalObject()
    }

    inner class FinalObject {
        val i = 1
    }
}
