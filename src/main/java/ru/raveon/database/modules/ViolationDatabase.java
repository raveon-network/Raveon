package ru.raveon.database.modules;

import lombok.RequiredArgsConstructor;
import ru.raveon.Raveon;
import ru.raveon.api.database.DatabaseManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

@RequiredArgsConstructor
public class ViolationDatabase implements ViolationStorage {
    private final DatabaseManager databaseManager;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    public CompletableFuture<Void> createTable() {
        return CompletableFuture.runAsync(() -> {
            String query = "CREATE TABLE IF NOT EXISTS violations (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "ac_version VARCHAR(50), " +
                    "verbose TEXT, " +
                    "check_name VARCHAR(100), " +
                    "vls INT, " +
                    "server VARCHAR(64), " +
                    "timestamp BIGINT" +
                    ");";
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.execute();

                String indexQuery = "CREATE INDEX IF NOT EXISTS idx_player_uuid ON violations(player_uuid);";
                try (PreparedStatement indexStmt = conn.prepareStatement(indexQuery)) {
                    indexStmt.execute();
                }

            } catch (SQLException e) {
                Raveon.INSTANCE.getLogger().log(Level.SEVERE, "Could not create violations table", e);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> logAlert(UUID uniqueId, String acVersion, String verbose, String checkName, int vls) {
        return CompletableFuture.runAsync(() -> {
            String query = "INSERT INTO violations (player_uuid, ac_version, verbose, check_name, vls, server, timestamp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?);";

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, uniqueId.toString());
                stmt.setString(2, acVersion);
                stmt.setString(3, verbose);
                stmt.setString(4, checkName);
                stmt.setInt(5, vls);
                stmt.setString(6, Raveon.serverName());
                stmt.setLong(7, System.currentTimeMillis());

                stmt.executeUpdate();

            } catch (SQLException e) {
                Raveon.INSTANCE.getLogger().log(Level.SEVERE, "Failed to log alert for player: " + uniqueId, e);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Integer> getLogCount(UUID uniqueId) {
        return CompletableFuture.supplyAsync(() -> {
            String query = "SELECT COUNT(*) as count FROM violations WHERE player_uuid = ?;";
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, uniqueId.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("count");
                    }
                }
            } catch (SQLException e) {
                Raveon.INSTANCE.getLogger().log(Level.SEVERE, "Failed to get log count for UUID: " + uniqueId, e);
            }
            return 0;
        }, executorService);
    }

    @Override
    public CompletableFuture<List<ViolationRecord>> getViolations(UUID uniqueId, int page, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<ViolationRecord> violations = new ArrayList<>();
            String query = "SELECT * FROM violations WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?;";

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, uniqueId.toString());
                stmt.setInt(2, limit);
                stmt.setInt(3, page * limit);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        violations.add(mapResultSetToRecord(rs));
                    }
                }
            } catch (SQLException e) {
                Raveon.INSTANCE.getLogger().log(Level.SEVERE, "Failed to get violations for UUID: " + uniqueId, e);
            }
            return violations;
        }, executorService);
    }

    @Override
    public CompletableFuture<List<ViolationRecord>> getAllViolations() {
        return CompletableFuture.supplyAsync(() -> {
            List<ViolationRecord> violations = new ArrayList<>();
            String query = "SELECT * FROM violations ORDER BY timestamp DESC;";

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query);
                 ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    violations.add(mapResultSetToRecord(rs));
                }
            } catch (SQLException e) {
                Raveon.INSTANCE.getLogger().log(Level.SEVERE, "Failed to get all violations from database", e);
            }
            return violations;
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(executorService::shutdown);
    }

    private ViolationRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
        return new ViolationRecord(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("check_name"),
                rs.getInt("vls"),
                rs.getString("server"),
                rs.getLong("timestamp"),
                rs.getString("verbose")
        );
    }
}