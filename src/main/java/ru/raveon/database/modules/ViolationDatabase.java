package ru.raveon.database.modules;

import lombok.RequiredArgsConstructor;

import ru.raveon.Raveon;
import ru.raveon.api.database.DatabaseManager;
import ru.raveon.api.database.DatabaseInitialization;
import ru.raveon.database.model.ViolationRecord;
import ru.raveon.database.storage.ViolationStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

@RequiredArgsConstructor
public class ViolationDatabase implements ViolationStorage {
    private final DatabaseManager databaseManager;
    private final DatabaseInitialization initialization = new DatabaseInitialization();
    private long nextRetentionCleanup;

    @Override
    public synchronized CompletableFuture<Void> createTable() {
        return initialization.ensure(databaseManager, () -> {
            String identity = databaseManager.getSqlByDataType(
                    "BIGINT AUTO_INCREMENT PRIMARY KEY", "INTEGER PRIMARY KEY AUTOINCREMENT");
            String query = "CREATE TABLE IF NOT EXISTS violations (id " + identity
                    + ", player_uuid VARCHAR(36) NOT NULL, ac_version VARCHAR(50), verbose TEXT,"
                    + " check_name VARCHAR(100), vls INT, server VARCHAR(64), timestamp BIGINT)";
            try (Connection connection = databaseManager.getConnection();
                    PreparedStatement statement = connection.prepareStatement(query)) {
                statement.execute();
                createIndex(connection, "idx_violations_player_time", "player_uuid, timestamp");
                createIndex(connection, "idx_violations_timestamp", "timestamp");
                return null;
            } catch (SQLException exception) {
                throw failure("Could not create violations table", exception);
            }
        });
    }

    private void createIndex(Connection connection, String name, String columns) throws SQLException {
        if (indexExists(connection, name)) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "CREATE INDEX " + name + " ON violations(" + columns + ")")) {
            statement.execute();
        } catch (SQLException exception) {
            if (!indexExists(connection, name)) throw exception;
        }
    }

    private boolean indexExists(Connection connection, String name) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, "violations", false, false)) {
            while (indexes.next())
                if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
        }
        return false;
    }

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<Void> ready = createTable();
        return databaseManager.submit(() -> {
            ready.join();
            return task.get();
        });
    }

    @Override
    public CompletableFuture<Void> logAlert(
            UUID uniqueId, String acVersion, String verbose, String checkName, int vls) {
        String server = Raveon.serverName();
        long receivedAt = System.currentTimeMillis();
        return submit(() -> {
            String query = "INSERT INTO violations (player_uuid, ac_version, verbose, check_name,"
                    + " vls, server, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (Connection connection = databaseManager.getConnection();
                    PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, uniqueId.toString());
                statement.setString(2, acVersion);
                statement.setString(3, verbose);
                statement.setString(4, checkName);
                statement.setInt(5, vls);
                statement.setString(6, server);
                statement.setLong(7, receivedAt);
                statement.executeUpdate();
                try {
                    cleanupRetention(connection);
                } catch (SQLException exception) {
                    databaseManager.logFailure(Level.WARNING, "Violation retention cleanup failed", exception);
                }
                return null;
            } catch (SQLException exception) {
                throw failure("Failed to log alert for " + uniqueId, exception);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> getLogCount(UUID uniqueId) {
        return submit(() -> {
            try (Connection connection = databaseManager.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT COUNT(*) FROM violations WHERE player_uuid = ?")) {
                statement.setString(1, uniqueId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? (int) Math.min(Integer.MAX_VALUE, rows.getLong(1)) : 0;
                }
            } catch (SQLException exception) {
                throw failure("Failed to count violations for " + uniqueId, exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<ViolationRecord>> getViolations(UUID uniqueId, int page, int limit) {
        if (page < 0 || limit < 1 || limit > 100)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid violations page"));
        return submit(() -> {
            List<ViolationRecord> violations = new ArrayList<>();
            String query = "SELECT player_uuid, check_name, vls, server, timestamp, verbose"
                    + " FROM violations WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?";
            try (Connection connection = databaseManager.getConnection();
                    PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, uniqueId.toString());
                statement.setInt(2, limit);
                statement.setLong(3, (long) page * limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) violations.add(mapResultSetToRecord(rows));
                }
                return violations;
            } catch (SQLException exception) {
                throw failure("Failed to load violations for " + uniqueId, exception);
            }
        });
    }

    private synchronized void cleanupRetention(Connection connection) throws SQLException {
        int days = databaseManager.getRetentionDays();
        long now = System.currentTimeMillis();
        if (days == 0 || now < nextRetentionCleanup) return;
        nextRetentionCleanup = now + 300_000L;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM violations WHERE id IN (SELECT id FROM (SELECT id FROM violations "
                        + "WHERE timestamp < ? ORDER BY timestamp LIMIT 1000) expired)")) {
            statement.setLong(1, now - TimeUnit.DAYS.toMillis(days));
            if (statement.executeUpdate() == 1000) nextRetentionCleanup = now + 1000L;
        }
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return databaseManager.shutdown();
    }

    private CompletionException failure(String message, SQLException exception) {
        databaseManager.logFailure(Level.SEVERE, message, exception);
        return new CompletionException(exception);
    }

    private ViolationRecord mapResultSetToRecord(ResultSet rows) throws SQLException {
        return new ViolationRecord(UUID.fromString(rows.getString("player_uuid")),
                rows.getString("check_name"), rows.getInt("vls"), rows.getString("server"),
                rows.getLong("timestamp"), rows.getString("verbose"));
    }
}
