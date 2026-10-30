package ru.raveon.manager.alert;

import lombok.Getter;

import ru.raveon.Raveon;
import ru.raveon.database.modules.PlayerProbabilityDatabase;
import ru.raveon.database.modules.ViolationDatabase;
import ru.raveon.database.storage.PlayerProbabilityStorage;
import ru.raveon.database.storage.ViolationStorage;
import ru.raveon.manager.anticheat.PlayerAnalysisSnapshot;
import ru.raveon.model.ModelId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ViolationManager {
    private final String anticheatVersion = Raveon.INSTANCE.getDescription().getVersion();
    private final PlayerProbabilityStorage probabilityStorage;
    private final ViolationStorage violationStorage;
    private final Map<ModelId, Map<UUID, Deque<Double>>> probabilitiesByModel = modelMaps();
    private final Map<ModelId, Map<UUID, PlayerAnalysisSnapshot>> analysisByModel = modelMaps();

    private static <T> Map<ModelId, Map<UUID, T>> modelMaps() {
        Map<ModelId, Map<UUID, T>> result = new EnumMap<>(ModelId.class);
        for (ModelId model : ModelId.values()) result.put(model, new ConcurrentHashMap<>());
        return result;
    }

    public ViolationManager() {
        probabilityStorage =
                new PlayerProbabilityDatabase(
                        Raveon.INSTANCE.getMainConfigManager().getDatabaseManager());
        probabilityStorage.createTable();
        violationStorage =
                new ViolationDatabase(Raveon.INSTANCE.getMainConfigManager().getDatabaseManager());
        violationStorage.createTable();
    }

    public void shutdown() {
        probabilityStorage.shutdown().join();
        violationStorage.shutdown().join();
    }

    public void addAiProbability(UUID id, double chance, boolean save, ModelId model) {
        probabilitiesByModel
                .get(model)
                .compute(
                        id,
                        (key, values) -> {
                            Deque<Double> history = values == null ? new ArrayDeque<>() : values;
                            synchronized (history) {
                                history.addLast(chance);
                                int limit =
                                        Math.max(
                                                1,
                                                Raveon.INSTANCE
                                                        .getMainConfigManager()
                                                        .getMaxLocalEntries());
                                while (history.size() > limit) history.removeFirst();
                            }
                            return history;
                        });
        if (save) probabilityStorage.addProbability(id, chance, model);
    }

    public Deque<Double> getLocalProbabilities(UUID id, ModelId model) {
        Deque<Double> history = probabilitiesByModel.get(model).get(id);
        if (history == null) return new ArrayDeque<>();
        synchronized (history) {
            return new ArrayDeque<>(history);
        }
    }

    public double getAverageProbability(UUID id, double fallback, ModelId model) {
        return getLocalProbabilities(id, model).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(fallback);
    }

    public void updateAnalysisSnapshot(UUID id, double probability, double buffer, ModelId model) {
        if (Double.isFinite(probability) && Double.isFinite(buffer))
            analysisByModel
                    .get(model)
                    .put(
                            id,
                            new PlayerAnalysisSnapshot(
                                    Math.max(0, Math.min(1, probability)), Math.max(0, buffer)));
    }

    public PlayerAnalysisSnapshot getAnalysisSnapshot(UUID id) {
        return getAnalysisSnapshot(id, ModelId.PRO);
    }

    public PlayerAnalysisSnapshot getAnalysisSnapshot(UUID id, ModelId model) {
        return analysisByModel.get(model).getOrDefault(id, PlayerAnalysisSnapshot.EMPTY);
    }

    public void removePlayerData(UUID id) {
        probabilitiesByModel.values().forEach(values -> values.remove(id));
        analysisByModel.values().forEach(values -> values.remove(id));
    }

}
