package ru.raveon.database.storage;

import ru.raveon.api.database.DatabaseStorage;
import ru.raveon.model.ModelId;
import ru.raveon.database.model.PlayerAIProbabilityData;

import java.util.List;
import java.util.Collection;
import ru.raveon.database.model.PlayerHistoryPage;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerProbabilityStorage extends DatabaseStorage {
    CompletableFuture<Void> ensurePlayer(UUID uniqueId, String playerName);

    CompletableFuture<UUID> resolvePlayerUuid(String playerName);

    CompletableFuture<List<PlayerAIProbabilityData>> getPlayersData(Collection<UUID> uniqueIds, int recentEntries, ModelId model);

    CompletableFuture<PlayerHistoryPage> getPlayerHistory(UUID uniqueId, int page, int pageSize, ModelId model);

    CompletableFuture<PlayerAIProbabilityData> getPlayerDataByUUID(UUID uniqueId, ModelId model);

    CompletableFuture<PlayerAIProbabilityData> getPlayerDataByName(String playerName, ModelId model);

    CompletableFuture<PlayerAIProbabilityData> getOrCreatePlayerData(
            UUID uniqueId, String playerName, ModelId model);

    CompletableFuture<Boolean> addProbability(UUID uniqueId, double probability, ModelId model);

    CompletableFuture<List<Double>> getProbabilities(UUID uniqueId, ModelId model);

    CompletableFuture<Boolean> deletePlayerData(UUID uniqueId);

}
