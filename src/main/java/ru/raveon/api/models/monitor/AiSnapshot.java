package ru.raveon.api.models.monitor;

public record AiSnapshot(
        double probability,
        double average,
        double buffer,
        double trend
) {
    public AiSnapshot(double probability, double buffer, double trend) {
        this(probability, probability, buffer, trend);
    }
}
