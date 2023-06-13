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
package spp.probe.services.common.model

import spp.protocol.instrument.throttle.ThrottleStep
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

open class HitThrottle(private val limit: Int, private val step: ThrottleStep) {

    private val lastReset = AtomicLong(-1)
    private val hitCount = AtomicInteger(0)
    val totalHitCount = AtomicInteger(0)
    val totalLimitedCount = AtomicInteger(0)

    open fun isRateLimited(): Boolean {
        if (hitCount.getAndIncrement() < limit) {
            if (lastReset.get() == -1L) {
                lastReset.set(System.currentTimeMillis())
            }

            totalHitCount.incrementAndGet()
            return false
        }

        return if (System.currentTimeMillis() - lastReset.get() > step.toMillis(1)) {
            hitCount.set(1)
            totalHitCount.incrementAndGet()
            lastReset.set(System.currentTimeMillis())
            false
        } else {
            totalLimitedCount.incrementAndGet()
            true
        }
    }

    class NOP : HitThrottle(-1, ThrottleStep.SECOND) {
        override fun isRateLimited(): Boolean {
            totalHitCount.incrementAndGet()
            return false
        }
    }
}
