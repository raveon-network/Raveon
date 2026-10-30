package ru.raveon.api.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import ru.raveon.api.configuration.RedisConfig;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Logger;

public class RedisManager {
    private final RedisConfig config;
    private final ThreadPoolExecutor executor;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE / 2);
    private volatile RedisClient redisClient;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile boolean closed;
    private long nextConnect = Long.MIN_VALUE;

    public RedisManager(RedisConfig config) {
        this.config = config;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), task -> {
                    Thread thread = new Thread(task, "Raveon-redis");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        if (config.isEnable()) execute(redis -> true);
    }

    public boolean isEnabled() { return config.isEnable() && !closed; }

    public <T> CompletableFuture<T> execute(Function<RedisCommands<String, String>, T> task) {
        if (!isEnabled())
            return CompletableFuture.failedFuture(new IllegalStateException("Redis is disabled or closed"));
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        try {
            executor.execute(() -> {
                try {
                    if (!result.isDone()) result.complete(task.apply(connect().sync()));
                } catch (RuntimeException exception) {
                    long now = System.nanoTime();
                    if (now >= nextConnect) nextConnect = now + TimeUnit.SECONDS.toNanos(5);
                    StatefulRedisConnection<String, String> failed = connection;
                    connection = null;
                    if (failed != null) failed.closeAsync();
                    result.completeExceptionally(exception);
                    warn("Redis operation failed; using local presence: " + exception.getMessage());
                } finally {
                    pending.remove(result);
                }
            });
        } catch (RejectedExecutionException exception) {
            pending.remove(result);
            result.completeExceptionally(exception);
            warn("Redis queue is full; operation rejected");
        }
        return result;
    }

    private StatefulRedisConnection<String, String> connect() {
        if (closed) throw new IllegalStateException("Redis is closed");
        if (connection != null && connection.isOpen()) return connection;
        if (System.nanoTime() < nextConnect)
            throw new IllegalStateException("Redis reconnect backoff");
        if (redisClient == null) {
            RedisURI.Builder builder = RedisURI.Builder.redis(config.getHost(), config.getPort())
                    .withTimeout(Duration.ofMillis(config.getTimeout()));
            if (!config.getPassword().isEmpty()) builder.withPassword(config.getPassword().toCharArray());
            redisClient = RedisClient.create(builder.build());
        }
        if (closed) {
            redisClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            throw new IllegalStateException("Redis is closed");
        }
        connection = redisClient.connect();
        if (closed) {
            redisClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            throw new IllegalStateException("Redis is closed");
        }
        return connection;
    }

    public synchronized void onDisable() {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
        RejectedExecutionException error = new RejectedExecutionException("Redis is closing");
        pending.forEach(future -> future.completeExceptionally(error));
        pending.clear();
        RedisClient client = redisClient;
        if (client != null) client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }

    private void warn(String message) {
        long now = System.nanoTime();
        long previous = lastWarning.get();
        if (now - previous >= TimeUnit.SECONDS.toNanos(5)
                && lastWarning.compareAndSet(previous, now))
            Logger.getLogger("Raveon").warning(message);
    }
}
