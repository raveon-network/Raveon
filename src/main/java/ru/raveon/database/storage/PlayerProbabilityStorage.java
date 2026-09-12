package ru.raveon.database.storage;

import ru.raveon.api.database.DatabaseStorage;
import ru.raveon.database.model.PlayerAIProbabilityData;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerProbabilityStorage extends DatabaseStorage {
    CompletableFuture<Boolean> savePlayerData(PlayerAIProbabilityData data);

    CompletableFuture<PlayerAIProbabilityData> getPlayerDataByUUID(UUID uniqueId);

    CompletableFuture<PlayerAIProbabilityData> getPlayerDataByName(String playerName);

    CompletableFuture<PlayerAIProbabilityData> getOrCreatePlayerData(UUID uniqueId, String playerName);

    CompletableFuture<Boolean> addProbability(UUID uniqueId, double probability);

    CompletableFuture<List<Double>> getProbabilities(UUID uniqueId);

    CompletableFuture<Boolean> deletePlayerData(UUID uniqueId);

    CompletableFuture<List<PlayerAIProbabilityData>> getAllPlayersData();
}
