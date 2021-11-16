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
