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

import org.springframework.expression.Expression
import spp.protocol.instrument.HitThrottle
import spp.protocol.instrument.LiveInstrument

class ActiveLiveInstrument @JvmOverloads constructor(
    val instrument: LiveInstrument,
    val expression: Expression? = null
) {
    val throttle: HitThrottle? = instrument.throttle.let {
        if (it != null) {
            HitThrottle(it.limit, it.step)
        } else {
            null
        }
    }
    var isRemoval = false
    var isLive = false

    val isFinished: Boolean
        get() = if (instrument.expiresAt != null && System.currentTimeMillis() >= instrument.expiresAt!!) {
            true
        } else {
            instrument.hitLimit != -1 && (throttle != null && throttle.totalHitCount >= instrument.hitLimit)
        }
}
