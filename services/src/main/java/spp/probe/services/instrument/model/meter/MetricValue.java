package spp.probe.services.instrument.model.meter;

import java.io.Serializable;

public class MetricValue implements Serializable {

    private final MetricValueType valueType;
    private final String number;
    private final String supplier;

    public MetricValue(MetricValueType valueType, String number, String supplier) {
        this.valueType = valueType;
        this.number = number;
        this.supplier = supplier;
    }

    public MetricValueType getValueType() {
        return valueType;
    }

    public String getNumber() {
        return number;
    }

    public String getSupplier() {
        return supplier;
    }
}
