package spp.probe.services.instrument;

import spp.probe.services.common.model.ActiveLiveInstrument;

import java.lang.instrument.Instrumentation;

public interface LiveInstrumentApplier {

    void apply(Instrumentation inst, ActiveLiveInstrument instrument);
}
