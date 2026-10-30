package ru.raveon.api.models.monitor;

import ru.raveon.model.ModelId;

public record AiSnapshot(
        double probability, double average, double buffer, double trend, ModelId model) {
    public AiSnapshot(double probability, double average, double buffer, double trend) {
        this(probability, average, buffer, trend, ModelId.PRO);
    }

    public AiSnapshot(double probability, double buffer, double trend) {
        this(probability, probability, buffer, trend, ModelId.PRO);
    }
}
