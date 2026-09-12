package ru.raveon.database.storage;

import ru.raveon.api.database.DatabaseStorage;
import ru.raveon.database.model.ViolationRecord;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ViolationStorage extends DatabaseStorage {
    CompletableFuture<Void> logAlert(UUID uniqueId, String acVersion, String verbose, String checkName, int vls);

    CompletableFuture<Integer> getLogCount(UUID uniqueId);

    CompletableFuture<List<ViolationRecord>> getViolations(UUID uniqueId, int page, int limit);

    CompletableFuture<List<ViolationRecord>> getAllViolations();
}