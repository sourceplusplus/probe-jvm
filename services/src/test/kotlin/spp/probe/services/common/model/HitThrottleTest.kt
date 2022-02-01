/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.probe.services.common.model

import junit.framework.TestCase
import org.junit.Test
import spp.protocol.instrument.throttle.HitThrottle
import spp.protocol.instrument.throttle.ThrottleStep
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HitThrottleTest {

    @Test
    @Throws(Exception::class)
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
    @Throws(Exception::class)
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
    @Throws(Exception::class)
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
