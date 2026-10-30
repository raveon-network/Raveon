package ru.raveon.database.modules;

import lombok.RequiredArgsConstructor;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import ru.raveon.Raveon;
import ru.raveon.model.ModelId;
import ru.raveon.api.database.DatabaseManager;
import ru.raveon.api.database.DatabaseInitialization;
import ru.raveon.api.models.data.PlayerData;
import ru.raveon.database.model.PlayerAIProbabilityData;
import ru.raveon.database.model.PlayerAIProbabilityData.Prob;
import ru.raveon.database.model.PlayerAIProbabilityData.ProbSnapshot;
import ru.raveon.database.storage.PlayerProbabilityStorage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import ru.raveon.database.model.PlayerHistoryPage;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

@RequiredArgsConstructor
public class PlayerProbabilityDatabase implements PlayerProbabilityStorage {
    private static final String PLAYER_TABLE = "player_probabilities";
    private static final String SNAPSHOT_TABLE = "player_probability_snapshots";
    private static final String ENTRY_TABLE = "player_probability_entries";
    private static final String USERNAME_INDEX = "idx_player_probabilities_username";

    private final DatabaseManager databaseManager;
    private final DatabaseInitialization initialization = new DatabaseInitialization();
    private long nextRetentionCleanup;

    @Override
    public synchronized CompletableFuture<Void> createTable() {
        return initialization.ensure(databaseManager, () -> {
            try (Connection connection = databaseManager.getConnection()) {
                createTables(connection);
                createIndexIfAbsent(connection, PLAYER_TABLE, USERNAME_INDEX, "username");
                for (ModelId model : ModelId.values()) {
                    createIndexIfAbsent(connection, entryTable(model),
                            (model == ModelId.PRO ? "idx_probability_received_at" : "idx_ultra_probability_received_at"), "received_at");
                    createIndexIfAbsent(connection, snapshotTable(model),
                            (model == ModelId.PRO ? "idx_snapshot_created_at" : "idx_ultra_snapshot_created_at"), "created_at");
                }
                return null;
            } catch (SQLException exception) {
                log(Level.SEVERE, "Could not create probability tables", exception);
                throw new CompletionException(exception);
            }
        });
    }

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<Void> ready = createTable();
        return databaseManager.submit(() -> {
            ready.join();
            return task.get();
        });
    }

    @Override
    public CompletableFuture<PlayerAIProbabilityData> getPlayerDataByUUID(UUID uniqueId, ModelId model) {
        return submit(
                () -> {
                    String query =
                            "SELECT uuid, username, last_server, created_at, updated_at "
                                    + "FROM "
                                    + PLAYER_TABLE
                                    + " WHERE uuid = ?";

                    try (Connection connection = databaseManager.getConnection();
                            PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setString(1, uniqueId.toString());

                        try (ResultSet resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                return null;
                            }

                            PlayerAIProbabilityData data = mapPlayer(resultSet, model);
                            data.setProbSnapshots(loadSnapshots(connection, uniqueId, model));
                            return data;
                        }
                    } catch (SQLException exception) {
                        log(
                                Level.SEVERE,
                                "Failed to get player data by UUID: " + uniqueId,
                                exception);
                        throw new CompletionException(exception);
                    }
                });
    }

    @Override
    public CompletableFuture<PlayerAIProbabilityData> getPlayerDataByName(String playerName, ModelId model) {
        return submit(
                () -> {
                    String query =
                            "SELECT uuid, username, last_server, created_at, updated_at "
                                    + "FROM "
                                    + PLAYER_TABLE
                                    + " WHERE username = ?";

                    try (Connection connection = databaseManager.getConnection();
                            PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setString(1, playerName);

                        try (ResultSet resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                return null;
                            }

                            PlayerAIProbabilityData data = mapPlayer(resultSet, model);
                            data.setProbSnapshots(
                                    loadSnapshots(connection, data.getPlayerData().getUniqueId(), model));
                            return data;
                        }
                    } catch (SQLException exception) {
                        log(
                                Level.SEVERE,
                                "Failed to get player data by name: " + playerName,
                                exception);
                        throw new CompletionException(exception);
                    }
                });
    }

    @Override
    public CompletableFuture<PlayerAIProbabilityData> getOrCreatePlayerData(
            UUID uniqueId, String playerName, ModelId model) {
        return getPlayerDataByUUID(uniqueId, model)
                .thenCompose(
                        data -> {
                            if (data != null) {
                                return CompletableFuture.completedFuture(data);
                            }

                            return ensurePlayer(uniqueId, playerName)
                                    .thenCompose(ignored -> getPlayerDataByUUID(uniqueId, model));
                        });
    }

    @Override
    public CompletableFuture<Boolean> addProbability(UUID uniqueId, double probability, ModelId model) {
        String username = resolveUsername(uniqueId, null);
        String serverName = Raveon.serverId();
        long receivedAt = System.currentTimeMillis();
        int maxEntries = Math.max(1, Raveon.INSTANCE.getMainConfigManager().getMaxProbEntries());

        return submit(
                () ->
                        addProbability(
                                uniqueId,
                                username,
                                probability,
                                serverName,
                                receivedAt,
                                maxEntries,
                                model));
    }

    @Override
    public CompletableFuture<List<Double>> getProbabilities(UUID uniqueId, ModelId model) {
        return getPlayerDataByUUID(uniqueId, model)
                .thenApply(data -> data == null ? new ArrayList<>() : data.getProbabilities());
    }

    @Override
    public CompletableFuture<Boolean> deletePlayerData(UUID uniqueId) {
        return submit(
                () -> {
                    try (Connection connection = databaseManager.getConnection()) {
                        connection.setAutoCommit(false);

                        try {
                            deleteSnapshotData(connection, uniqueId);
                            int affectedRows = deletePlayer(connection, uniqueId);
                            connection.commit();
                            return affectedRows > 0;
                        } catch (SQLException exception) {
                            rollback(connection);
                            log(
                                    Level.SEVERE,
                                    "Failed to delete player data for UUID: " + uniqueId,
                                    exception);
                            return false;
                        }
                    } catch (SQLException exception) {
                        log(
                                Level.SEVERE,
                                "Failed to open connection while deleting player data",
                                exception);
                        return false;
                    }
                });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return databaseManager.shutdown();
    }

    @Override
    public CompletableFuture<Void> ensurePlayer(UUID uniqueId, String playerName) {
        return submit(() -> {
            try (Connection connection = databaseManager.getConnection()) {
                upsertPlayer(connection, createDefaultPlayerData(uniqueId, playerName));
                return null;
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to initialize player " + uniqueId, exception);
                throw new CompletionException(exception);
            }
        });
    }

    @Override
    public CompletableFuture<UUID> resolvePlayerUuid(String playerName) {
        return submit(() -> {
            try (Connection connection = databaseManager.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT uuid FROM " + PLAYER_TABLE + " WHERE username = ? ORDER BY updated_at DESC LIMIT 1")) {
                statement.setString(1, playerName);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? UUID.fromString(rows.getString(1)) : null;
                }
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to resolve player " + playerName, exception);
                throw new CompletionException(exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<PlayerAIProbabilityData>> getPlayersData(
            Collection<UUID> uniqueIds, int recentEntries, ModelId model) {
        if (recentEntries < 1 || recentEntries > 1000 || uniqueIds.size() > 1000)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player summary limit exceeded"));
        List<UUID> requested = uniqueIds.stream().distinct().toList();
        if (requested.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return submit(() -> {
            Map<UUID, PlayerAIProbabilityData> players = new LinkedHashMap<>();
            try (Connection connection = databaseManager.getConnection()) {
                for (int start = 0; start < requested.size(); start += 250) {
                    List<UUID> batch = requested.subList(start, Math.min(start + 250, requested.size()));
                    String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                    try (PreparedStatement query = connection.prepareStatement(
                            "SELECT uuid, username, last_server, created_at, updated_at FROM "
                                    + PLAYER_TABLE + " WHERE uuid IN (" + placeholders + ")")) {
                        for (int i = 0; i < batch.size(); i++) query.setString(i + 1, batch.get(i).toString());
                        try (ResultSet rows = query.executeQuery()) {
                            while (rows.next()) {
                                PlayerAIProbabilityData data = mapPlayer(rows, model);
                                players.put(data.getPlayerData().getUniqueId(), data);
                            }
                        }
                    }
                    String recent = "SELECT * FROM (SELECT player_uuid, chance, server_name, received_at, snapshot_index, entry_index FROM "
                            + entryTable(model)
                            + " WHERE player_uuid = ? ORDER BY snapshot_index DESC, entry_index DESC LIMIT ?) recent";
                    try (PreparedStatement query = connection.prepareStatement(
                            String.join(" UNION ALL ", Collections.nCopies(batch.size(), recent))
                                    + " ORDER BY player_uuid, snapshot_index DESC, entry_index DESC")) {
                        for (int i = 0; i < batch.size(); i++) {
                            query.setString(i * 2 + 1, batch.get(i).toString());
                            query.setInt(i * 2 + 2, recentEntries);
                        }
                        try (ResultSet rows = query.executeQuery()) {
                            while (rows.next()) {
                                PlayerAIProbabilityData data = players.get(UUID.fromString(rows.getString("player_uuid")));
                                if (data == null) continue;
                                if (data.getProbSnapshots().isEmpty())
                                    data.getProbSnapshots().add(new ProbSnapshot(0, new ArrayList<>()));
                                data.getProbSnapshots().get(0).getProbabilities().add(mapProbability(rows));
                            }
                        }
                    }
                }
                for (PlayerAIProbabilityData data : players.values()) {
                    if (data.getProbSnapshots().isEmpty()) continue;
                    ProbSnapshot snapshot = data.getProbSnapshots().get(0);
                    List<Prob> entries = snapshot.getProbabilities();
                    Collections.reverse(entries);
                    snapshot.setCreatedAt(entries.get(0).getReceivedAt());
                    Prob latest = entries.get(entries.size() - 1);
                    data.setUpdatedAt(latest.getReceivedAt());
                    data.setLastServerName(latest.getServerName());
                }
                return List.copyOf(players.values());
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to load player summaries", exception);
                throw new CompletionException(exception);
            }
        });
    }

    @Override
    public CompletableFuture<PlayerHistoryPage> getPlayerHistory(UUID uniqueId, int page, int pageSize, ModelId model) {
        if (page < 0 || pageSize < 1 || pageSize > 54)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid history page"));
        return submit(() -> {
            try (Connection connection = databaseManager.getConnection();
                    PreparedStatement playerQuery = connection.prepareStatement(
                            "SELECT uuid, username, last_server, created_at, updated_at FROM "
                                    + PLAYER_TABLE + " WHERE uuid = ?");
                    PreparedStatement countQuery = connection.prepareStatement(
                            "SELECT COUNT(*) FROM " + snapshotTable(model) + " WHERE player_uuid = ?")) {
                playerQuery.setString(1, uniqueId.toString());
                PlayerAIProbabilityData data;
                try (ResultSet rows = playerQuery.executeQuery()) {
                    if (!rows.next()) return new PlayerHistoryPage(null, 0, 0);
                    data = mapPlayer(rows, model);
                }
                countQuery.setString(1, uniqueId.toString());
                int total;
                try (ResultSet rows = countQuery.executeQuery()) {
                    total = rows.next() ? Math.toIntExact(rows.getLong(1)) : 0;
                }
                int selectedPage = Math.min(page, Math.max(0, (total - 1) / pageSize));
                data.setProbSnapshots(loadSnapshots(connection, uniqueId, selectedPage * pageSize, pageSize, model));
                return new PlayerHistoryPage(data, selectedPage, total);
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to load history page for " + uniqueId, exception);
                throw new CompletionException(exception);
            }
        });
    }

    private synchronized void cleanupRetention(Connection connection) throws SQLException {
        int days = databaseManager.getRetentionDays();
        long now = System.currentTimeMillis();
        if (days == 0 || now < nextRetentionCleanup) return;
        nextRetentionCleanup = now + 300_000L;
        long cutoff = now - java.util.concurrent.TimeUnit.DAYS.toMillis(days);
        for (ModelId model : ModelId.values()) {
            try (PreparedStatement entries = connection.prepareStatement(
                            "DELETE FROM " + entryTable(model) + " WHERE (player_uuid, snapshot_index, entry_index) IN "
                                    + "(SELECT player_uuid, snapshot_index, entry_index FROM (SELECT player_uuid, "
                                    + "snapshot_index, entry_index FROM " + entryTable(model)
                                    + " WHERE received_at < ? ORDER BY received_at LIMIT 1000) expired)");
                    PreparedStatement snapshots = connection.prepareStatement(
                            "DELETE FROM " + snapshotTable(model) + " WHERE (player_uuid, snapshot_index) IN "
                                    + "(SELECT player_uuid, snapshot_index FROM (SELECT s.player_uuid, s.snapshot_index FROM "
                                    + snapshotTable(model) + " s WHERE s.created_at < ? AND NOT EXISTS "
                                    + "(SELECT 1 FROM " + entryTable(model) + " e WHERE e.player_uuid = s.player_uuid "
                                    + "AND e.snapshot_index = s.snapshot_index) LIMIT 1000) expired)")) {
                entries.setLong(1, cutoff);
                int deleted = entries.executeUpdate();
                snapshots.setLong(1, cutoff);
                snapshots.executeUpdate();
                connection.commit();
                if (deleted == 1000) nextRetentionCleanup = now + 1000L;
            }
        }
    }

    private static String snapshotTable(ModelId model) {
        return model == ModelId.PRO ? SNAPSHOT_TABLE : "ultra_probability_snapshots";
    }

    private static String entryTable(ModelId model) {
        return model == ModelId.PRO ? ENTRY_TABLE : "ultra_probability_entries";
    }

    private void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + PLAYER_TABLE
                    + " (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(16) NOT NULL,"
                    + " last_server VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
            for (ModelId model : ModelId.values()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + snapshotTable(model)
                        + " (player_uuid VARCHAR(36) NOT NULL, snapshot_index INTEGER NOT NULL,"
                        + " created_at BIGINT NOT NULL, PRIMARY KEY (player_uuid, snapshot_index),"
                        + " FOREIGN KEY (player_uuid) REFERENCES " + PLAYER_TABLE + "(uuid) ON DELETE CASCADE)");
                statement.execute("CREATE TABLE IF NOT EXISTS " + entryTable(model)
                        + " (player_uuid VARCHAR(36) NOT NULL, snapshot_index INTEGER NOT NULL,"
                        + " entry_index INTEGER NOT NULL, chance DOUBLE NOT NULL,"
                        + " server_name VARCHAR(64) NOT NULL, received_at BIGINT NOT NULL,"
                        + " PRIMARY KEY (player_uuid, snapshot_index, entry_index),"
                        + " FOREIGN KEY (player_uuid, snapshot_index) REFERENCES " + snapshotTable(model)
                        + "(player_uuid, snapshot_index) ON DELETE CASCADE)");
            }
        }
    }

    private void createIndexIfAbsent(
            Connection connection, String tableName, String indexName, String columnName)
            throws SQLException {
        if (indexExists(connection, tableName, indexName)) {
            return;
        }

        String query = "CREATE INDEX " + indexName + " ON " + tableName + " (" + columnName + ")";
        try (Statement statement = connection.createStatement()) {
            statement.execute(query);
        } catch (SQLException exception) {
            if (!indexExists(connection, tableName, indexName)) throw exception;
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();

        try (ResultSet indexes = metadata.getIndexInfo(catalog, null, tableName, false, false)) {
            while (indexes.next()) {
                String existingIndex = indexes.getString("INDEX_NAME");
                if (existingIndex != null
                        && existingIndex
                                .toLowerCase(Locale.ROOT)
                                .equals(indexName.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean addProbability(
            UUID uniqueId,
            String username,
            double probability,
            String serverName,
            long receivedAt,
            int maxEntries,
            ModelId model) {
        try (Connection connection = databaseManager.getConnection()) {
            connection.setAutoCommit(false);

            try {
                PlayerAIProbabilityData playerData =
                        new PlayerAIProbabilityData(
                                new PlayerData(username, uniqueId),
                                serverName,
                                new ArrayList<>(),
                                receivedAt,
                                receivedAt,
                                model);

                upsertPlayer(connection, playerData);

                SnapshotPosition position = findSnapshotPosition(connection, uniqueId, maxEntries, model);
                if (position.createSnapshot()) {
                    insertSnapshot(connection, uniqueId, position.snapshotIndex(), receivedAt, model);
                }

                insertEntry(
                        connection,
                        uniqueId,
                        position.snapshotIndex(),
                        position.entryIndex(),
                        probability,
                        serverName,
                        receivedAt,
                        model);

                connection.commit();
                try {
                    cleanupRetention(connection);
                } catch (SQLException exception) {
                    log(Level.WARNING, "Probability retention cleanup failed", exception);
                }
                return true;
            } catch (SQLException exception) {
                rollback(connection);
                log(Level.SEVERE, "Failed to add probability for UUID: " + uniqueId, exception);
                throw new CompletionException(exception);
            }
        } catch (SQLException exception) {
            log(Level.SEVERE, "Failed to open connection while adding probability", exception);
            throw new CompletionException(exception);
        }
    }

    private SnapshotPosition findSnapshotPosition(
            Connection connection, UUID uniqueId, int maxEntries, ModelId model) throws SQLException {
        String query = "SELECT s.snapshot_index, COUNT(e.entry_index) AS entry_count, "
                + "COALESCE(MAX(e.entry_index), -1) AS max_entry_index FROM "
                + "(SELECT snapshot_index FROM " + snapshotTable(model)
                + " WHERE player_uuid = ? ORDER BY snapshot_index DESC LIMIT 1) s "
                + "LEFT JOIN " + entryTable(model)
                + " e ON e.player_uuid = ? AND e.snapshot_index = s.snapshot_index "
                + "GROUP BY s.snapshot_index";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, uniqueId.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new SnapshotPosition(0, 0, true);
                }

                int snapshotIndex = resultSet.getInt("snapshot_index");
                int entryCount = resultSet.getInt("entry_count");
                int nextEntryIndex = resultSet.getInt("max_entry_index") + 1;

                if (entryCount >= maxEntries) {
                    return new SnapshotPosition(snapshotIndex + 1, 0, true);
                }

                return new SnapshotPosition(snapshotIndex, nextEntryIndex, false);
            }
        }
    }

    private void upsertPlayer(Connection connection, PlayerAIProbabilityData data)
            throws SQLException {
        String query =
                databaseManager.getSqlByDataType(
                        "INSERT INTO "
                                + PLAYER_TABLE
                                + " (uuid, username, last_server, created_at, updated_at) VALUES"
                                + " (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE username ="
                                + " VALUES(username), last_server = VALUES(last_server), updated_at"
                                + " = VALUES(updated_at)",
                        "INSERT INTO "
                                + PLAYER_TABLE
                                + " (uuid, username, last_server, created_at, updated_at) VALUES"
                                + " (?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET username ="
                                + " excluded.username, last_server = excluded.last_server,"
                                + " updated_at = excluded.updated_at");

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, data.getPlayerData().getUniqueId().toString());
            statement.setString(2, data.getPlayerData().getUsername());
            statement.setString(3, data.getLastServerName());
            statement.setLong(4, data.getCreatedAt());
            statement.setLong(5, data.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private void insertSnapshot(
            Connection connection, UUID uniqueId, int snapshotIndex, long createdAt, ModelId model)
            throws SQLException {
        String query =
                "INSERT INTO "
                        + snapshotTable(model)
                        + " (player_uuid, snapshot_index, created_at) VALUES (?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uniqueId.toString());
            statement.setInt(2, snapshotIndex);
            statement.setLong(3, createdAt);
            statement.executeUpdate();
        }
    }

    private void insertEntry(
            Connection connection,
            UUID uniqueId,
            int snapshotIndex,
            int entryIndex,
            double probability,
            String serverName,
            long receivedAt,
            ModelId model)
            throws SQLException {
        String query =
                "INSERT INTO "
                        + entryTable(model)
                        + " (player_uuid, snapshot_index, entry_index, chance, server_name,"
                        + " received_at) VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uniqueId.toString());
            statement.setInt(2, snapshotIndex);
            statement.setInt(3, entryIndex);
            statement.setDouble(4, probability);
            statement.setString(5, serverName);
            statement.setLong(6, receivedAt);
            statement.executeUpdate();
        }
    }

    private List<ProbSnapshot> loadSnapshots(Connection connection, UUID uniqueId, ModelId model)
            throws SQLException {
        List<ProbSnapshot> snapshots = loadSnapshots(connection, uniqueId, 0, 100, model);
        Collections.reverse(snapshots);
        return snapshots;
    }

    private List<ProbSnapshot> loadSnapshots(
            Connection connection, UUID uniqueId, int offset, int limit, ModelId model) throws SQLException {
        String query = "SELECT s.snapshot_index, s.created_at AS snapshot_created_at, "
                + "e.entry_index, e.chance, e.server_name, e.received_at FROM "
                + "(SELECT player_uuid, snapshot_index, created_at FROM " + snapshotTable(model)
                + " WHERE player_uuid = ? ORDER BY snapshot_index DESC LIMIT ? OFFSET ?) s "
                + "LEFT JOIN " + entryTable(model)
                + " e ON e.player_uuid = s.player_uuid AND e.snapshot_index = s.snapshot_index "
                + "ORDER BY s.snapshot_index DESC, e.entry_index";
        List<ProbSnapshot> snapshots = new ArrayList<>();
        int currentIndex = -1;
        ProbSnapshot current = null;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uniqueId.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int index = rows.getInt("snapshot_index");
                    if (current == null || index != currentIndex) {
                        current = new ProbSnapshot(rows.getLong("snapshot_created_at"), new ArrayList<>());
                        snapshots.add(current);
                        currentIndex = index;
                    }
                    if (rows.getObject("entry_index") != null)
                        current.getProbabilities().add(mapProbability(rows));
                }
            }
        }
        return snapshots;
    }

    private PlayerAIProbabilityData mapPlayer(ResultSet resultSet, ModelId model) throws SQLException {
        UUID uuid = UUID.fromString(resultSet.getString("uuid"));
        String username = resolveUsername(uuid, resultSet.getString("username"));

        return new PlayerAIProbabilityData(
                new PlayerData(username, uuid),
                resultSet.getString("last_server"),
                new ArrayList<>(),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"), model);
    }

    private Prob mapProbability(ResultSet resultSet) throws SQLException {
        return new Prob(
                resultSet.getDouble("chance"),
                resultSet.getString("server_name"),
                resultSet.getLong("received_at"));
    }

    private PlayerAIProbabilityData createDefaultPlayerData(UUID uniqueId, String playerName) {
        long now = System.currentTimeMillis();

        return new PlayerAIProbabilityData(
                new PlayerData(resolveUsername(uniqueId, playerName), uniqueId),
                Raveon.serverId(),
                new ArrayList<>(),
                now,
                now,
                ModelId.PRO);
    }

    private void deleteSnapshotData(Connection connection, UUID uniqueId) throws SQLException {
        for (ModelId model : ModelId.values()) {
            executeDelete(connection, entryTable(model), "player_uuid", uniqueId.toString());
            executeDelete(connection, snapshotTable(model), "player_uuid", uniqueId.toString());
        }
    }

    private int deletePlayer(Connection connection, UUID uniqueId) throws SQLException {
        String query = "DELETE FROM " + PLAYER_TABLE + " WHERE uuid = ?";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uniqueId.toString());
            return statement.executeUpdate();
        }
    }

    private void executeDelete(
            Connection connection, String tableName, String columnName, String value)
            throws SQLException {
        String query = "DELETE FROM " + tableName + " WHERE " + columnName + " = ?";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private String resolveUsername(UUID uniqueId, String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            return playerName;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uniqueId);
        String offlinePlayerName = offlinePlayer.getName();
        if (offlinePlayerName != null && !offlinePlayerName.isBlank()) {
            return offlinePlayerName;
        }

        return uniqueId.toString().replace("-", "").substring(0, 16);
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            log(Level.SEVERE, "Failed to rollback probability transaction", rollbackException);
        }
    }

    private void log(Level level, String message, Throwable throwable) {
        if (throwable == null) {
            databaseManager.getPlugin().getLogger().log(level, message);
            return;
        }

        databaseManager.logFailure(level, message, throwable);
    }

    private record SnapshotPosition(int snapshotIndex, int entryIndex, boolean createSnapshot) {}
}
