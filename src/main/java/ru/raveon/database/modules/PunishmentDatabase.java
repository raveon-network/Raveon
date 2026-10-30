package ru.raveon.database.modules;

import ru.raveon.api.database.DatabaseManager;
import ru.raveon.api.database.DatabaseInitialization;
import ru.raveon.config.anticheat.PunishmentConfigManager.PunishGroupConfig;
import ru.raveon.model.ModelId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.logging.Level;

public final class PunishmentDatabase {
    private final DatabaseManager database;
    private final String serverKey;
    private final LongSupplier clock;
    private final Object transactionLock = new Object();
    private final DatabaseInitialization initialization = new DatabaseInitialization();
    private long nextCleanup;

    public PunishmentDatabase(DatabaseManager database, String server) {
        this(database, server, System::currentTimeMillis);
    }

    public PunishmentDatabase(DatabaseManager database, String server, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
        try {
            this.serverKey = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(server.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public synchronized CompletableFuture<Void> createTable() {
        return initialization.ensure(database, () -> {
            String identity = database.getSqlByDataType(
                    "BIGINT AUTO_INCREMENT PRIMARY KEY", "INTEGER PRIMARY KEY AUTOINCREMENT");
            try (Connection connection = database.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "CREATE TABLE IF NOT EXISTS punishment_flags (id " + identity
                            + ", server_key VARCHAR(64) NOT NULL, player_uuid VARCHAR(36) NOT NULL,"
                            + " group_name VARCHAR(64) NOT NULL, model VARCHAR(16) NOT NULL,"
                            + " check_name VARCHAR(100) NOT NULL, expires_at BIGINT NOT NULL)"
                            + database.getSqlByDataType(" ENGINE=InnoDB", ""))) {
                statement.execute();
                createIndex(connection, "idx_punishment_group",
                        "server_key, player_uuid, group_name, expires_at");
                createIndex(connection, "idx_punishment_expiry", "expires_at");
                return null;
            } catch (SQLException error) {
                throw failure("Cannot create punishment counters", error);
            }
        });
    }

    public CompletableFuture<List<CountedGroup>> record(
            UUID player, ModelId model, String check, List<PunishGroupConfig> groups, long receivedAt) {
        List<PunishGroupConfig> accepted = List.copyOf(groups);
        if (accepted.isEmpty()) return CompletableFuture.completedFuture(List.of());
        CompletableFuture<Void> ready = createTable();
        return database.submit(() -> {
            ready.join();
            synchronized (transactionLock) {
                return recordTransaction(player, model, check, accepted, receivedAt);
            }
        });
    }

    private List<CountedGroup> recordTransaction(
            UUID player, ModelId model, String check, List<PunishGroupConfig> groups, long receivedAt) {
        long now = Math.max(receivedAt, clock.getAsLong());
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            List<CountedGroup> result = new ArrayList<>();
            try {
                for (PunishGroupConfig group : groups) {
                    long expiresAt = Math.addExact(receivedAt, group.removeViolationsAfterMillis());
                    if (expiresAt <= now) continue;
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO punishment_flags (server_key, player_uuid, group_name,"
                            + " model, check_name, expires_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                        bindGroup(insert, player, group.name());
                        insert.setString(4, model.key());
                        insert.setString(5, check);
                        insert.setLong(6, expiresAt);
                        insert.executeUpdate();
                    }
                    try (PreparedStatement query = connection.prepareStatement(
                            "SELECT COUNT(*), COALESCE(SUM(CASE WHEN model = ? AND check_name = ?"
                            + " THEN 1 ELSE 0 END), 0) FROM punishment_flags"
                            + " WHERE server_key = ? AND player_uuid = ? AND group_name = ? AND expires_at > ?")) {
                        query.setString(1, model.key());
                        query.setString(2, check);
                        query.setString(3, serverKey);
                        query.setString(4, player.toString());
                        query.setString(5, group.name());
                        query.setLong(6, now);
                        try (ResultSet rows = query.executeQuery()) {
                            if (!rows.next()) throw new SQLException("Missing punishment counter result");
                            result.add(new CountedGroup(group, rows.getLong(1), rows.getLong(2)));
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
            connection.setAutoCommit(true);
            cleanup(connection, now);
            return List.copyOf(result);
        } catch (SQLException error) {
            throw failure("Cannot update punishment counters for " + player, error);
        }
    }

    private void bindGroup(PreparedStatement statement, UUID player, String group) throws SQLException {
        statement.setString(1, serverKey);
        statement.setString(2, player.toString());
        statement.setString(3, group);
    }

    private void cleanup(Connection connection, long now) {
        if (now < nextCleanup) return;
        nextCleanup = now + 60_000;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM punishment_flags WHERE id IN (SELECT id FROM (SELECT id"
                + " FROM punishment_flags WHERE expires_at <= ? ORDER BY expires_at LIMIT 1000) expired)")) {
            statement.setLong(1, now);
            if (statement.executeUpdate() == 1000) nextCleanup = now + 1000;
        } catch (SQLException error) {
            database.logFailure(Level.WARNING, "Cannot expire punishment counters", error);
        }
    }

    private void createIndex(Connection connection, String name, String columns) throws SQLException {
        if (indexExists(connection, name)) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "CREATE INDEX " + name + " ON punishment_flags(" + columns + ")")) {
            statement.execute();
        } catch (SQLException error) {
            if (!indexExists(connection, name)) throw error;
        }
    }

    private boolean indexExists(Connection connection, String name) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, "punishment_flags", false, false)) {
            while (indexes.next())
                if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
        }
        return false;
    }

    private CompletionException failure(String message, SQLException error) {
        database.logFailure(Level.SEVERE, message, error);
        return new CompletionException(error);
    }

    public record CountedGroup(PunishGroupConfig group, long total, long checkViolations) {}
}
