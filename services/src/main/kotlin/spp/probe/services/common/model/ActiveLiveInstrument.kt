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

import org.springframework.expression.Expression
import spp.protocol.instrument.LiveInstrument
import java.util.concurrent.atomic.AtomicBoolean

class ActiveLiveInstrument @JvmOverloads constructor(
    val instrument: LiveInstrument,
    val expression: Expression? = null
) {
    val throttle: HitThrottle = instrument.throttle.let {
        if (it != null && it.limit != -1) {
            HitThrottle(it.limit, it.step)
        } else {
            HitThrottle.NOP()
        }
    }
    var isRemoval = false
    var isApplied = false

    val isFinished: Boolean
        get() = if (instrument.expiresAt != null && System.currentTimeMillis() >= instrument.expiresAt!!) {
            true
        } else {
            instrument.hitLimit != -1 && throttle.totalHitCount >= instrument.hitLimit
        }
}
