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
package spp.probe.services.common.model

import spp.protocol.instrument.throttle.ThrottleStep
import kotlin.jvm.Transient

class HitThrottle(private val limit: Int, step: ThrottleStep) {

    private val step: ThrottleStep

    @Transient
    private var lastReset: Long = -1

    @Transient
    private var hitCount = 0

    @Transient
    var totalHitCount = 0
        private set

    @Transient
    var totalLimitedCount = 0
        private set

    init {
        this.step = step
    }

    fun isRateLimited(): Boolean {
        if (hitCount++ < limit) {
            totalHitCount++
            return false
        }
        return if (System.currentTimeMillis() - lastReset > step.toMillis(1)) {
            hitCount = 1
            totalHitCount++
            lastReset = System.currentTimeMillis()
            false
        } else {
            totalLimitedCount++
            true
        }
    }
}
