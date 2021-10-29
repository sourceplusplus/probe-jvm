package spp.probe.services.instrument.model;

import org.apache.skywalking.apm.agent.core.meter.MeterType;
import org.springframework.expression.Expression;
import spp.probe.services.common.model.HitThrottle;
import spp.probe.services.common.model.Location;
import spp.probe.services.instrument.model.meter.MetricValue;
import spp.probe.services.instrument.model.meter.MetricValueType;

import java.util.function.Supplier;

public class LiveMeter extends LiveInstrument {

    private final String meterName;
    private final MeterType meterType;
    private final MetricValue metricValue;
    private final transient boolean staticNumber;
    private transient Supplier<Number> staticSupplier;

    public LiveMeter(String id, Location location, Expression expression, int hitLimit, HitThrottle throttle,
                     Long expiresAt, String meterName, String meterType, String valueType, String supplier) {
        super(id, location, expression, hitLimit, throttle, expiresAt);
        this.meterName = meterName;
        this.meterType = MeterType.valueOf(meterType);
        this.staticNumber = "NUMBER".equals(valueType);

        if (staticNumber) {
            this.metricValue = new MetricValue(MetricValueType.valueOf(valueType), supplier, null);
            if (supplier.contains(".")) {
                staticSupplier = () -> Double.valueOf(supplier);
            } else {
                staticSupplier = () -> Long.valueOf(supplier);
            }
        } else {
            this.metricValue = new MetricValue(MetricValueType.valueOf(valueType), null, supplier);
            //todo: parse supplier
            throw new UnsupportedOperationException("not supported yet");
        }
    }

    public Supplier<Number> getSupplier() {
        if (staticNumber) {
            return staticSupplier;
        } else {
            throw new UnsupportedOperationException("not supported yet");
        }
    }

    public MeterType getMeterType() {
        return meterType;
    }
}
