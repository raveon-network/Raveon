package ru.raveon.api.database;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
    private final Plugin plugin;
    private final String username;
    private final String password;
    private final String table;
    private final DatabaseType dataType;
    private final String url;
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
        this.username = username;
        this.password = password;
        this.table = table;
        this.dataType = dataType;
        this.url = url != null ? url : dataType.generateUrl(host, port,
                dataType == DatabaseType.SQLITE
                        ? new File(plugin.getDataFolder(), table + ".db").getAbsolutePath() : table);
        this.retentionDays = retentionDays;
        int workers = dataType == DatabaseType.SQLITE ? 1 : 4;
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
        Connection connection = dataType == DatabaseType.MYSQL
                ? DriverManager.getConnection(url, username, password) : DriverManager.getConnection(url);
        try {
            if (dataType == DatabaseType.SQLITE) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA busy_timeout = 5000");
                    statement.execute("PRAGMA foreign_keys = ON");
                }
            }
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
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
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    RejectedExecutionException error =
                            new RejectedExecutionException("Database shutdown deadline exceeded");
                    pending.forEach(task -> task.result.completeExceptionally(error));
                    pending.clear();
                    closing.completeExceptionally(error);
                    plugin.getLogger().warning("Database shutdown timed out; unfinished operations cancelled");
                } else {
                    closing.complete(null);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                pending.forEach(task -> task.result.completeExceptionally(exception));
                pending.clear();
                closing.completeExceptionally(exception);
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
