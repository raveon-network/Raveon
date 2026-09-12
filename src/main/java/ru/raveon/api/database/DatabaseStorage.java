package ru.raveon.api.database;

import java.util.concurrent.CompletableFuture;

public interface DatabaseStorage {
    CompletableFuture<Void> createTable();

    CompletableFuture<Void> shutdown();
}