package ru.raveon.database.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import ru.raveon.model.ModelId;
import ru.raveon.api.models.data.PlayerData;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
public class PlayerAIProbabilityData {
    private PlayerData playerData;
    private String lastServerName;

    private List<ProbSnapshot> probSnapshots;

    private long createdAt;
    private long updatedAt;
    private ModelId model;

    public List<Prob> getProbabilityEntries() {
        if (probSnapshots == null || probSnapshots.isEmpty()) {
            return Collections.emptyList();
        }

        return probSnapshots.stream()
                .filter(Objects::nonNull)
                .map(ProbSnapshot::getProbabilities)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<Double> getProbabilities() {
        return getProbabilityEntries().stream().map(Prob::getChance).collect(Collectors.toList());
    }

    public double getAverageProbability() {
        return getProbabilityEntries().stream().mapToDouble(Prob::getChance).average().orElse(0.0);
    }

    @Data
    @AllArgsConstructor
    public static class ProbSnapshot {
        private long createdAt;
        private List<Prob> probabilities;
    }

    @Data
    @AllArgsConstructor
    public static class Prob {
        private double chance;
        private String serverName;
        private long receivedAt;
    }
}
