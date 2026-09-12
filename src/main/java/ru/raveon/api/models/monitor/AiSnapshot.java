package ru.raveon.api.models.monitor;

public record AiSnapshot(
        double probability,
        double buffer,
        double trend
) {
}
