package ru.raveon.manager.anticheat;

public record PlayerAnalysisSnapshot(
        double probability,
        double buffer
) {
    public static final PlayerAnalysisSnapshot EMPTY =
            new PlayerAnalysisSnapshot(0.0D, 0.0D);
}