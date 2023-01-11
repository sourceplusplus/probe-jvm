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
package integration.breakpoint

import integration.ProbeIntegrationTest
import io.vertx.core.eventbus.ReplyException
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.error.LiveInstrumentException

class NonExistentClass : ProbeIntegrationTest() {

    @Test
    fun `apply immediately non existent`(): Unit = runBlocking {
        val instrumentId = "breakpoint-non-existent-class"
        try {
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        source = "non-existent-class",
                        line = 1,
                        service = "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = instrumentId
                )
            ).await()
        } catch (ex: ReplyException) {
            assertEquals(500, ex.failureCode())
            assertTrue(ex.cause is LiveInstrumentException)
            assertEquals(
                LiveInstrumentException.ErrorType.CLASS_NOT_FOUND,
                (ex.cause as LiveInstrumentException).errorType
            )
            return@runBlocking
        }
        fail("Should not be able to apply breakpoint immediately to non-existent class")
    }
}
