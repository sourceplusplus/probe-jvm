package spp.probe.services.common.model

import kotlin.jvm.JvmOverloads
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.HitThrottle
import org.springframework.expression.Expression

class ActiveLiveInstrument @JvmOverloads constructor(
    val instrument: LiveInstrument,
    val expression: Expression? = null
) {
    val throttle: HitThrottle
    var isRemoval = false
    var isLive = false

    init {
        throttle = HitThrottle(instrument.throttle.limit, instrument.throttle.step)
    }

    val isFinished: Boolean
        get() = if (instrument.expiresAt != null && System.currentTimeMillis() >= instrument.expiresAt!!) {
            true
        } else {
            instrument.hitLimit != -1 && throttle.totalHitCount >= instrument.hitLimit
        }
}