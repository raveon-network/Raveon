package ru.raveon.api.database;

import com.zaxxer.hikari.HikariDataSource;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;

public class DatabaseManager {
    private static final AtomicLong POOL_IDS = new AtomicLong();
    private final Plugin plugin;
    private final String table;
    private final DatabaseType dataType;
    private final HikariDataSource dataSource;
    private final int retentionDays;
    private final ThreadPoolExecutor executor;
    private final Set<DatabaseTask<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastRejectionWarning = new AtomicLong(Long.MIN_VALUE / 2);
    private final AtomicLong lastFailure = new AtomicLong(Long.MIN_VALUE / 2);
    private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE / 2);
    private CompletableFuture<Void> closing;

    public DatabaseManager(Plugin plugin, String host, int port, String username, String password,
            String table, DatabaseType dataType) {
        this(plugin, host, port, username, password, table, dataType, null, 0);
    }

    public DatabaseManager(Plugin plugin, String host, int port, String username, String password,
            String table, DatabaseType dataType, String url) {
        this(plugin, host, port, username, password, table, dataType, url, 0);
    }

    private DatabaseManager(Plugin plugin, String host, int port, String username, String password,
            String table, DatabaseType dataType, String url, int retentionDays) {
        this.plugin = plugin;
        this.table = table;
        this.dataType = dataType;
        String jdbcUrl = url != null ? url : dataType.generateUrl(host, port,
                dataType == DatabaseType.SQLITE
                        ? new File(plugin.getDataFolder(), table + ".db").getAbsolutePath() : table);
        this.retentionDays = retentionDays;
        int workers = dataType == DatabaseType.SQLITE ? 1 : 4;
        // Start the pool on the first worker request, never while loading server configuration.
        dataSource = new HikariDataSource();
        dataSource.setPoolName("Raveon-database-" + POOL_IDS.incrementAndGet());
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setMaximumPoolSize(workers);
        dataSource.setMinimumIdle(workers);
        dataSource.setConnectionTimeout(3000);
        dataSource.setValidationTimeout(1000);
        dataSource.setInitializationFailTimeout(-1);
        if (dataType == DatabaseType.SQLITE) {
            dataSource.addDataSourceProperty("busy_timeout", "5000");
            dataSource.addDataSourceProperty("foreign_keys", "true");
            dataSource.setMaxLifetime(0);
            dataSource.setKeepaliveTime(0);
        } else {
            dataSource.setUsername(username);
            dataSource.setPassword(password);
            dataSource.addDataSourceProperty("tcpKeepAlive", "true");
        }
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(512), runnable -> {
                    Thread thread = new Thread(runnable, "Raveon-database");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static DatabaseManager fromSection(ConfigurationSection section, Plugin plugin) {
        return fromSection(section, plugin, null);
    }

    public static DatabaseManager fromSection(
            ConfigurationSection section, Plugin plugin, DatabaseManager defaultManager) {
        if (section == null) {
            if (defaultManager != null) return defaultManager;
            throw new IllegalArgumentException("Missing database configuration");
        }
        String host = section.getString("host", "");
        int port = section.getInt("port", 3306);
        String username = section.getString("username", "");
        String password = section.getString("password", "");
        String table = section.getString("name", "database");
        DatabaseType type = DatabaseType.getByName(section.getString("data_type", "SQLITE"));
        int retention = section.getInt("retention_days", 0);
        if (type == null || port < 1 || port > 65535 || table == null || table.isBlank()
                || retention < 0 || retention > 36500)
            throw new IllegalArgumentException("Invalid database configuration");
        return new DatabaseManager(plugin, host, port, username, password, table, type, null, retention);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        DatabaseTask<T> task = new DatabaseTask<>(supplier);
        pending.add(task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            pending.remove(task);
            task.result.completeExceptionally(exception);
            long now = System.nanoTime();
            long previous = lastRejectionWarning.get();
            if (now - previous >= TimeUnit.SECONDS.toNanos(5)
                    && lastRejectionWarning.compareAndSet(previous, now))
                plugin.getLogger().warning("Database queue is full or closed; operation rejected");
        }
        return task.result;
    }

    public synchronized CompletableFuture<Void> shutdown() {
        if (closing != null) return closing;
        closing = new CompletableFuture<>();
        executor.shutdown();
        Thread closer = new Thread(() -> {
            Throwable failure = null;
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    RejectedExecutionException error =
                            new RejectedExecutionException("Database shutdown deadline exceeded");
                    pending.forEach(task -> task.result.completeExceptionally(error));
                    pending.clear();
                    failure = error;
                    plugin.getLogger().warning("Database shutdown timed out; unfinished operations cancelled");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                pending.forEach(task -> task.result.completeExceptionally(exception));
                pending.clear();
                failure = exception;
            } finally {
                try {
                    dataSource.close();
                } catch (RuntimeException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
                if (failure == null) closing.complete(null);
                else closing.completeExceptionally(failure);
            }
        }, "Raveon-database-close");
        closer.setDaemon(true);
        closer.start();
        return closing;
    }

    public void logFailure(Level level, String message, Throwable failure) {
        AtomicLong previousLog = level.intValue() >= Level.SEVERE.intValue() ? lastFailure : lastWarning;
        long now = System.nanoTime();
        long previous = previousLog.get();
        if (now - previous >= TimeUnit.SECONDS.toNanos(5) && previousLog.compareAndSet(previous, now))
            plugin.getLogger().log(level, message, failure);
    }

    private final class DatabaseTask<T> implements Runnable {
        private final Supplier<T> supplier;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private DatabaseTask(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void run() {
            try {
                if (!result.isDone()) result.complete(supplier.get());
            } catch (Throwable exception) {
                result.completeExceptionally(exception);
            } finally {
                pending.remove(this);
            }
        }
    }

    public String getSqlByDataType(String mysql, String sqlite) {
        return dataType == DatabaseType.MYSQL ? mysql : sqlite;
    }

    public Plugin getPlugin() { return plugin; }
    public String getTable() { return table; }
    public int getRetentionDays() { return retentionDays; }
}
