package spp.probe.services.instrument

import java.lang.instrument.Instrumentation
import spp.probe.services.common.model.ActiveLiveInstrument

fun interface LiveInstrumentApplier {
    fun apply(inst: Instrumentation, instrument: ActiveLiveInstrument)
}
