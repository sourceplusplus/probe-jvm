package spp.probe.services.instrument.model;

import org.springframework.expression.Expression;
import spp.probe.services.common.model.HitThrottle;
import spp.probe.services.common.model.Location;

public class LiveSpan extends LiveInstrument {

    public LiveSpan(String id, Location location, Expression expression, int hitLimit,
                    HitThrottle throttle, Long expiresAt) {
        super(id, location, expression, hitLimit, throttle, expiresAt);
    }
}
