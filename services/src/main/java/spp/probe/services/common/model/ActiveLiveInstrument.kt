package spp.probe.services.common.model;

import org.springframework.expression.Expression;
import spp.protocol.instrument.HitThrottle;
import spp.protocol.instrument.LiveInstrument;

public class ActiveLiveInstrument {

    private final LiveInstrument instrument;
    private final Expression expression;
    private final HitThrottle throttle;
    private boolean removal;
    private boolean live;

    public ActiveLiveInstrument(LiveInstrument instrument) {
        this(instrument, null);
    }

    public ActiveLiveInstrument(LiveInstrument instrument, Expression expression) {
        this.instrument = instrument;
        this.expression = expression;
        this.throttle = new HitThrottle(instrument.getThrottle().getLimit(), instrument.getThrottle().getStep());
    }

    public LiveInstrument getInstrument() {
        return instrument;
    }

    public boolean isRemoval() {
        return removal;
    }

    public void setRemoval(boolean removal) {
        this.removal = removal;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public boolean isLive() {
        return live;
    }

    public Expression getExpression() {
        return expression;
    }

    public HitThrottle getThrottle() {
        return throttle;
    }

    public boolean isFinished() {
        if (instrument.getExpiresAt() != null && System.currentTimeMillis() >= instrument.getExpiresAt()) {
            return true;
        } else {
            return instrument.getHitLimit() != -1 && throttle.getTotalHitCount() >= instrument.getHitLimit();
        }
    }
}
