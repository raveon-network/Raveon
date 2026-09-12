package ru.raveon.database.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import ru.raveon.Raveon;
import ru.raveon.api.models.data.PlayerData;

import java.util.ArrayList;
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

    public void addProbability(double chance, String serverName) {
        addProbability(chance, serverName, System.currentTimeMillis());
    }

    public void addProbability(double chance) {
        addProbability(chance, lastServerName);
    }

    public void addProbability(double chance, String serverName, long receivedAt) {
        List<ProbSnapshot> snapshots = getOrCreateProbSnapshots();
        ProbSnapshot snapshot = getWritableSnapshot(snapshots, receivedAt);
        String resolvedServerName = resolveServerName(serverName);

        snapshot.getProbabilities().add(new Prob(chance, resolvedServerName, receivedAt));
        lastServerName = resolvedServerName;
        updatedAt = receivedAt;
    }

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
        return getProbabilityEntries().stream()
                .map(Prob::getChance)
                .collect(Collectors.toList());
    }

    public double getAverageProbability() {
        return getProbabilityEntries().stream()
                .mapToDouble(Prob::getChance)
                .average()
                .orElse(0.0);
    }

    public int getProbabilityCount() {
        return getProbabilityEntries().size();
    }

    private List<ProbSnapshot> getOrCreateProbSnapshots() {
        if (probSnapshots == null) {
            probSnapshots = new ArrayList<>();
        }

        return probSnapshots;
    }

    private ProbSnapshot getWritableSnapshot(List<ProbSnapshot> snapshots, long receivedAt) {
        int maxEntries = Math.max(1, Raveon.INSTANCE
                .getMainConfigManager()
                .getMaxProbEntries());

        if (snapshots.isEmpty()) {
            return createSnapshot(snapshots, receivedAt);
        }

        ProbSnapshot snapshot = snapshots.get(snapshots.size() - 1);
        if (snapshot == null) {
            return createSnapshot(snapshots, receivedAt);
        }

        if (snapshot.getProbabilities() == null) {
            snapshot.setProbabilities(new ArrayList<>());
        }

        if (snapshot.getProbabilities().size() >= maxEntries) {
            return createSnapshot(snapshots, receivedAt);
        }

        return snapshot;
    }

    private String resolveServerName(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            return serverName;
        }

        if (lastServerName != null && !lastServerName.isBlank()) {
            return lastServerName;
        }

        return Raveon.serverId();
    }

    private ProbSnapshot createSnapshot(List<ProbSnapshot> snapshots, long createdAt) {
        ProbSnapshot snapshot = new ProbSnapshot(createdAt, new ArrayList<>());
        snapshots.add(snapshot);
        return snapshot;
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
