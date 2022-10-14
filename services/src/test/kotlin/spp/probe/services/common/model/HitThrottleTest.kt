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
package spp.probe.services.common.model

import junit.framework.TestCase
import org.junit.Test
import spp.protocol.instrument.throttle.ThrottleStep
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HitThrottleTest {

    @Test
    fun oneASecond() {
        val hitThrottle = HitThrottle(1, ThrottleStep.SECOND)
        val scheduler = Executors.newScheduledThreadPool(1)
        val beeperHandle = scheduler.scheduleAtFixedRate({ hitThrottle.isRateLimited() }, 0, 100, TimeUnit.MILLISECONDS)
        scheduler.schedule({ beeperHandle.cancel(true) }, 5, TimeUnit.SECONDS).get()
        scheduler.shutdown()
        TestCase.assertEquals(5f, hitThrottle.totalHitCount.toFloat(), 1f)
        TestCase.assertEquals(45f, hitThrottle.totalLimitedCount.toFloat(), 1f)
    }

    @Test
    fun twiceASecond() {
        val hitThrottle = HitThrottle(2, ThrottleStep.SECOND)
        val scheduler = Executors.newScheduledThreadPool(1)
        val beeperHandle = scheduler.scheduleAtFixedRate({ hitThrottle.isRateLimited() }, 0, 225, TimeUnit.MILLISECONDS)
        scheduler.schedule({ beeperHandle.cancel(true) }, 5, TimeUnit.SECONDS).get()
        scheduler.shutdown()
        TestCase.assertEquals(10f, hitThrottle.totalHitCount.toFloat(), 1f)
        TestCase.assertEquals(12f, hitThrottle.totalLimitedCount.toFloat(), 1f)
    }

    @Test
    fun fourTimesASecond() {
        val hitThrottle = HitThrottle(4, ThrottleStep.SECOND)
        val scheduler = Executors.newScheduledThreadPool(1)
        val beeperHandle = scheduler.scheduleAtFixedRate({ hitThrottle.isRateLimited() }, 0, 225, TimeUnit.MILLISECONDS)
        scheduler.schedule({ beeperHandle.cancel(true) }, 5, TimeUnit.SECONDS).get()
        scheduler.shutdown()
        TestCase.assertEquals(20f, hitThrottle.totalHitCount.toFloat(), 1f)
        TestCase.assertEquals(3f, hitThrottle.totalLimitedCount.toFloat(), 1f)
    }
}
