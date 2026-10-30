package ru.raveon.service.train;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class DatasetWriter implements AutoCloseable {
    private final Set<CompletableFuture<Boolean>> pending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;

    public DatasetWriter(int queueCapacity) {
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "Raveon-datasets");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<Boolean> append(Path file, String header, List<T> frames,
            Function<T, String> serialize) {
        if (frames.isEmpty()) return CompletableFuture.completedFuture(true);
        List<T> batch = List.copyOf(frames);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        pending.add(result);
        try {
            executor.execute(() -> {
                try {
                    if (result.isDone()) return;
                    Files.createDirectories(file.toAbsolutePath().getParent());
                    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                            StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                        long original = channel.size();
                        try {
                            StringBuilder text = new StringBuilder();
                            if (original == 0) text.append(header);
                            for (T frame : batch) text.append('\n').append(serialize.apply(frame));
                            ByteBuffer data = StandardCharsets.UTF_8.encode(text.toString());
                            channel.position(original);
                            while (data.hasRemaining()) channel.write(data);
                            channel.force(false);
                        } catch (Exception error) {
                            try {
                                channel.truncate(original);
                                channel.force(false);
                            } catch (IOException rollback) {
                                error.addSuppressed(rollback);
                            }
                            throw error;
                        }
                    }
                    result.complete(true);
                } catch (Exception error) {
                    result.completeExceptionally(error);
                } finally {
                    pending.remove(result);
                }
            });
        } catch (RejectedExecutionException error) {
            pending.remove(result);
            result.completeExceptionally(error);
        }
        return result;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(5, TimeUnit.SECONDS)) return;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        RejectedExecutionException error = new RejectedExecutionException("Dataset shutdown deadline exceeded");
        pending.forEach(result -> result.completeExceptionally(error));
        pending.clear();
        throw error;
    }
}
