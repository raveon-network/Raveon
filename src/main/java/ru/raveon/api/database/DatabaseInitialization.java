package ru.raveon.api.database;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class DatabaseInitialization {
    private static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(5);

    private CompletableFuture<Void> readiness;
    private long retryAfter = Long.MIN_VALUE;

    public synchronized CompletableFuture<Void> ensure(
            DatabaseManager database, Supplier<Void> initializer) {
        if (readiness != null && (!readiness.isCompletedExceptionally()
                || System.nanoTime() < retryAfter)) return readiness;
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        readiness = attempt;
        database.submit(initializer).whenComplete((ignored, error) -> {
            synchronized (this) {
                if (error == null) {
                    attempt.complete(null);
                } else {
                    retryAfter = System.nanoTime() + RETRY_DELAY_NANOS;
                    attempt.completeExceptionally(error);
                }
            }
        });
        return attempt;
    }
}
