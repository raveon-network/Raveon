package ru.raveon.manager.alert;

import lombok.Getter;
import ru.raveon.Raveon;
import ru.raveon.checks.Check;
import ru.raveon.database.modules.PlayerProbabilityDatabase;
import ru.raveon.database.modules.ViolationDatabase;
import ru.raveon.database.storage.PlayerProbabilityStorage;
import ru.raveon.database.storage.ViolationStorage;
import ru.raveon.manager.anticheat.PlayerAnalysisSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ViolationManager {
    private final String anticheatVersion = Raveon.INSTANCE.getDescription().getVersion();
    private final PlayerProbabilityStorage probabilityStorage;
    private final ViolationStorage violationStorage;
    private final Map<UUID, PlayerAnalysisSnapshot> analysisSnapshots = new ConcurrentHashMap<>();

    private final Map<UUID, Deque<Double>> localProbabilities = new ConcurrentHashMap<>();

    public ViolationManager() {
        probabilityStorage = new PlayerProbabilityDatabase(Raveon.INSTANCE.getMainConfigManager().getDatabaseManager());
        probabilityStorage.createTable();

        violationStorage = new ViolationDatabase(Raveon.INSTANCE.getMainConfigManager().getDatabaseManager());
        violationStorage.createTable();
    }

    public void shutdown() {
        probabilityStorage.shutdown().join();
        violationStorage.shutdown().join();
    }

    public void addAiProbability(UUID uniqueId, double chance, boolean saveToDatabase) {
        addToLocalCache(uniqueId, chance);

        if (!saveToDatabase) {
            return;
        }

        probabilityStorage.addProbability(uniqueId, chance);
    }

    public Deque<Double> getLocalProbabilities(UUID uniqueId) {
        return localProbabilities.getOrDefault(uniqueId, new ArrayDeque<>());
    }

    private void addToLocalCache(UUID uniqueId, double chance) {
        localProbabilities.compute(uniqueId, (id, probabilities) -> {
            Deque<Double> deque = probabilities == null ? new ArrayDeque<>() : probabilities;
            deque.addLast(chance);

            while (deque.size() > Raveon.INSTANCE.getMainConfigManager().getMaxLocalEntries()) {
                deque.pollFirst();
            }

            return deque;
        });
    }

    public void handleFlag(Check check, String verbose) {
        logAlert(check, verbose);

        String alertMessage = Raveon.INSTANCE.getMainConfigManager().getVerboseMessage()
                .replace("{check_name}", check.getCheckName())
                .replace("{player}", check.getPlayer().getName())
                .replace("{verbose}", verbose)
                .replace("{vl}", String.valueOf((int) check.getViolations()));

        Raveon.INSTANCE.getAlertManager().sendAlert(alertMessage);
    }

    public void logAlert(Check check, String verbose) {
        violationStorage.logAlert(
                check.getPlayer().getUuid(),
                anticheatVersion,
                verbose,
                check.getCheckName(),
                (int) check.getViolations()
        );
    }
    public void updateAnalysisSnapshot(
             UUID uniqueId,
            double probability,
            double buffer
    ) {
        if (!Double.isFinite(probability) || !Double.isFinite(buffer)) {
            return;
        }

        analysisSnapshots.put(
                uniqueId,
                new PlayerAnalysisSnapshot(
                        Math.max(0.0D, Math.min(1.0D, probability)),
                        Math.max(0.0D, buffer)
                )
        );
    }

    public PlayerAnalysisSnapshot getAnalysisSnapshot(UUID uniqueId) {
        return analysisSnapshots.getOrDefault(
                uniqueId,
                PlayerAnalysisSnapshot.EMPTY
        );
    }
    public void removePlayerData(UUID uniqueId) {
        localProbabilities.remove(uniqueId);
        analysisSnapshots.remove(uniqueId);
    }
}
