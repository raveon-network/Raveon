package ru.raveon.database.modules;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import ru.raveon.Raveon;
import ru.raveon.api.database.DatabaseManager;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

@RequiredArgsConstructor
public class PlayerProbabilityDatabase implements PlayerProbabilityStorage {
    private static final String PLAYER_TABLE = "player_probabilities";
    private static final String SNAPSHOT_TABLE = "player_probability_snapshots";
    private static final String ENTRY_TABLE = "player_probability_entries";
    private static final String USERNAME_INDEX = "idx_player_probabilities_username";

    private final DatabaseManager databaseManager;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    public CompletableFuture<Void> createTable() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.getConnection()) {
                createTables(connection);
                createIndexIfAbsent(connection, PLAYER_TABLE, USERNAME_INDEX, "username");
            } catch (SQLException exception) {
                log(Level.SEVERE, "Could not create probability tables", exception);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Boolean> savePlayerData(PlayerAIProbabilityData data) {
        return CompletableFuture.supplyAsync(() -> {
            if (!isValid(data)) {
                log(Level.WARNING, "Cannot save probability data without player UUID", null);
                return false;
            }

            PlayerData playerData = data.getPlayerData();
            normalizePlayerData(data, playerData.getUniqueId(), playerData.getUsername());

            try (Connection connection = databaseManager.getConnection()) {
                connection.setAutoCommit(false);

                try {
                    upsertPlayer(connection, data);
                    replaceSnapshots(connection, data);
                    connection.commit();
                    return true;
                } catch (SQLException exception) {
                    rollback(connection);
                    log(Level.SEVERE, "Failed to save player data for: " + data.getPlayerData().getUsername(), exception);
                    return false;
                }
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to open connection while saving player data", exception);
                return false;
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<PlayerAIProbabilityData> getPlayerDataByUUID(UUID uniqueId) {
        return CompletableFuture.supplyAsync(() -> {
            String query = "SELECT uuid, username, last_server, created_at, updated_at " +
                    "FROM " + PLAYER_TABLE + " WHERE uuid = ?";

            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, uniqueId.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return null;
                    }

                    PlayerAIProbabilityData data = mapPlayer(resultSet);
                    data.setProbSnapshots(loadSnapshots(connection, uniqueId));
                    return data;
                }
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to get player data by UUID: " + uniqueId, exception);
                return null;
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<PlayerAIProbabilityData> getPlayerDataByName(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            String query = "SELECT uuid, username, last_server, created_at, updated_at " +
                    "FROM " + PLAYER_TABLE + " WHERE username = ?";

            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, playerName);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return null;
                    }

                    PlayerAIProbabilityData data = mapPlayer(resultSet);
                    data.setProbSnapshots(loadSnapshots(connection, data.getPlayerData().getUniqueId()));
                    return data;
                }
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to get player data by name: " + playerName, exception);
                return null;
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<PlayerAIProbabilityData> getOrCreatePlayerData(UUID uniqueId, String playerName) {
        return getPlayerDataByUUID(uniqueId).thenCompose(data -> {
            if (data != null) {
                normalizePlayerData(data, uniqueId, playerName);
                return CompletableFuture.completedFuture(data);
            }

            PlayerAIProbabilityData createdData = createDefaultPlayerData(uniqueId, playerName);
            return savePlayerData(createdData).thenApply(saved -> createdData);
        });
    }

    @Override
    public CompletableFuture<Boolean> addProbability(UUID uniqueId, double probability) {
        String username = resolveUsername(uniqueId, null);
        String serverName = Raveon.serverId();
        long receivedAt = System.currentTimeMillis();
        int maxEntries = Math.max(1, Raveon.INSTANCE
                .getMainConfigManager()
                .getMaxProbEntries());

        return CompletableFuture.supplyAsync(() -> addProbability(
                uniqueId,
                username,
                probability,
                serverName,
                receivedAt,
                maxEntries
        ), executorService);
    }

    @Override
    public CompletableFuture<List<Double>> getProbabilities(UUID uniqueId) {
        return getPlayerDataByUUID(uniqueId).thenApply(data ->
                data == null ? new ArrayList<>() : data.getProbabilities()
        );
    }

    @Override
    public CompletableFuture<Boolean> deletePlayerData(UUID uniqueId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.getConnection()) {
                connection.setAutoCommit(false);

                try {
                    deleteSnapshotData(connection, uniqueId);
                    int affectedRows = deletePlayer(connection, uniqueId);
                    connection.commit();
                    return affectedRows > 0;
                } catch (SQLException exception) {
                    rollback(connection);
                    log(Level.SEVERE, "Failed to delete player data for UUID: " + uniqueId, exception);
                    return false;
                }
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to open connection while deleting player data", exception);
                return false;
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<List<PlayerAIProbabilityData>> getAllPlayersData() {
        return CompletableFuture.supplyAsync(() -> {
            String query = "SELECT " +
                    "p.uuid, p.username, p.last_server, p.created_at AS player_created_at, p.updated_at, " +
                    "s.snapshot_index, s.created_at AS snapshot_created_at, " +
                    "e.entry_index, e.chance, e.server_name, e.received_at " +
                    "FROM " + PLAYER_TABLE + " p " +
                    "LEFT JOIN " + SNAPSHOT_TABLE + " s ON s.player_uuid = p.uuid " +
                    "LEFT JOIN " + ENTRY_TABLE + " e ON e.player_uuid = s.player_uuid " +
                    "AND e.snapshot_index = s.snapshot_index " +
                    "ORDER BY p.uuid, s.snapshot_index, e.entry_index";

            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(query);
                 ResultSet resultSet = statement.executeQuery()) {
                return mapAllPlayers(resultSet);
            } catch (SQLException exception) {
                log(Level.SEVERE, "Failed to load all players data", exception);
                return new ArrayList<>();
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(executorService::shutdown);
    }

    private void createTables(Connection connection) throws SQLException {
        List<String> queries = List.of(
                "CREATE TABLE IF NOT EXISTS " + PLAYER_TABLE + " (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "username VARCHAR(16) NOT NULL, " +
                        "last_server VARCHAR(64) NOT NULL, " +
                        "created_at BIGINT NOT NULL, " +
                        "updated_at BIGINT NOT NULL" +
                        ")",
                "CREATE TABLE IF NOT EXISTS " + SNAPSHOT_TABLE + " (" +
                        "player_uuid VARCHAR(36) NOT NULL, " +
                        "snapshot_index INTEGER NOT NULL, " +
                        "created_at BIGINT NOT NULL, " +
                        "PRIMARY KEY (player_uuid, snapshot_index), " +
                        "FOREIGN KEY (player_uuid) REFERENCES " + PLAYER_TABLE + "(uuid) ON DELETE CASCADE" +
                        ")",
                "CREATE TABLE IF NOT EXISTS " + ENTRY_TABLE + " (" +
                        "player_uuid VARCHAR(36) NOT NULL, " +
                        "snapshot_index INTEGER NOT NULL, " +
                        "entry_index INTEGER NOT NULL, " +
                        "chance DOUBLE NOT NULL, " +
                        "server_name VARCHAR(64) NOT NULL, " +
                        "received_at BIGINT NOT NULL, " +
                        "PRIMARY KEY (player_uuid, snapshot_index, entry_index), " +
                        "FOREIGN KEY (player_uuid, snapshot_index) REFERENCES " + SNAPSHOT_TABLE +
                        "(player_uuid, snapshot_index) ON DELETE CASCADE" +
                        ")"
        );

        try (Statement statement = connection.createStatement()) {
            for (String query : queries) {
                statement.execute(query);
            }
        }
    }

    private void createIndexIfAbsent(
            Connection connection,
            String tableName,
            String indexName,
            String columnName
    ) throws SQLException {
        if (indexExists(connection, tableName, indexName)) {
            return;
        }

        String query = "CREATE INDEX " + indexName + " ON " + tableName + " (" + columnName + ")";
        try (Statement statement = connection.createStatement()) {
            statement.execute(query);
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();

        try (ResultSet indexes = metadata.getIndexInfo(catalog, null, tableName, false, false)) {
            while (indexes.next()) {
                String existingIndex = indexes.getString("INDEX_NAME");
                if (existingIndex != null && existingIndex.toLowerCase(Locale.ROOT)
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
            int maxEntries
    ) {
        try (Connection connection = databaseManager.getConnection()) {
            connection.setAutoCommit(false);

            try {
                PlayerAIProbabilityData playerData = new PlayerAIProbabilityData(
                        new PlayerData(username, uniqueId),
                        serverName,
                        new ArrayList<>(),
                        receivedAt,
                        receivedAt
                );

                upsertPlayer(connection, playerData);
                touchPlayer(connection, uniqueId, serverName, receivedAt);

                SnapshotPosition position = findSnapshotPosition(connection, uniqueId, maxEntries);
                if (position.createSnapshot()) {
                    insertSnapshot(connection, uniqueId, position.snapshotIndex(), receivedAt);
                }

                insertEntry(
                        connection,
                        uniqueId,
                        position.snapshotIndex(),
                        position.entryIndex(),
                        probability,
                        serverName,
                        receivedAt
                );

                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollback(connection);
                log(Level.SEVERE, "Failed to add probability for UUID: " + uniqueId, exception);
                return false;
            }
        } catch (SQLException exception) {
            log(Level.SEVERE, "Failed to open connection while adding probability", exception);
            return false;
        }
    }

    private SnapshotPosition findSnapshotPosition(
            Connection connection,
            UUID uniqueId,
            int maxEntries
    ) throws SQLException {
        String query = "SELECT s.snapshot_index, COUNT(e.entry_index) AS entry_count, " +
                "COALESCE(MAX(e.entry_index), -1) AS max_entry_index " +
                "FROM " + SNAPSHOT_TABLE + " s " +
                "LEFT JOIN " + ENTRY_TABLE + " e ON e.player_uuid = s.player_uuid " +
                "AND e.snapshot_index = s.snapshot_index " +
                "WHERE s.player_uuid = ? " +
                "GROUP BY s.snapshot_index " +
                "ORDER BY s.snapshot_index DESC LIMIT 1";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uniqueId.toString());

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

    private void upsertPlayer(Connection connection, PlayerAIProbabilityData data) throws SQLException {
        String query = databaseManager.getSqlByDataType(
                "INSERT INTO " + PLAYER_TABLE +
                        " (uuid, username, last_server, created_at, updated_at) VALUES (?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE username = VALUES(username), " +
                        "last_server = VALUES(last_server), updated_at = VALUES(updated_at)",
                "INSERT INTO " + PLAYER_TABLE +
                        " (uuid, username, last_server, created_at, updated_at) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, " +
                        "last_server = excluded.last_server, updated_at = excluded.updated_at"
        );

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, data.getPlayerData().getUniqueId().toString());
            statement.setString(2, data.getPlayerData().getUsername());
            statement.setString(3, data.getLastServerName());
            statement.setLong(4, data.getCreatedAt());
            statement.setLong(5, data.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private void touchPlayer(
            Connection connection,
            UUID uniqueId,
            String serverName,
            long updatedAt
    ) throws SQLException {
        String query = "UPDATE " + PLAYER_TABLE + " SET last_server = ?, updated_at = ? WHERE uuid = ?";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, serverName);
            statement.setLong(2, updatedAt);
            statement.setString(3, uniqueId.toString());
            statement.executeUpdate();
        }
    }

    private void replaceSnapshots(Connection connection, PlayerAIProbabilityData data) throws SQLException {
        UUID uniqueId = data.getPlayerData().getUniqueId();
        deleteSnapshotData(connection, uniqueId);

        List<ProbSnapshot> snapshots = data.getProbSnapshots();
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        for (int snapshotIndex = 0; snapshotIndex < snapshots.size(); snapshotIndex++) {
            ProbSnapshot snapshot = snapshots.get(snapshotIndex);
            if (snapshot == null) {
                continue;
            }

            insertSnapshot(connection, uniqueId, snapshotIndex, snapshot.getCreatedAt());
            insertSnapshotEntries(connection, uniqueId, snapshotIndex, snapshot);
        }
    }

    private void insertSnapshotEntries(
            Connection connection,
            UUID uniqueId,
            int snapshotIndex,
            ProbSnapshot snapshot
    ) throws SQLException {
        List<Prob> probabilities = snapshot.getProbabilities();
        if (probabilities == null || probabilities.isEmpty()) {
            return;
        }

        String query = "INSERT INTO " + ENTRY_TABLE +
                " (player_uuid, snapshot_index, entry_index, chance, server_name, received_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int entryIndex = 0; entryIndex < probabilities.size(); entryIndex++) {
                Prob probability = probabilities.get(entryIndex);
                if (probability == null) {
                    continue;
                }

                statement.setString(1, uniqueId.toString());
                statement.setInt(2, snapshotIndex);
                statement.setInt(3, entryIndex);
                statement.setDouble(4, probability.getChance());
                statement.setString(5, probability.getServerName());
                statement.setLong(6, probability.getReceivedAt());
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void insertSnapshot(
            Connection connection,
            UUID uniqueId,
            int snapshotIndex,
            long createdAt
    ) throws SQLException {
        String query = "INSERT INTO " + SNAPSHOT_TABLE +
                " (player_uuid, snapshot_index, created_at) VALUES (?, ?, ?)";

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
            long receivedAt
    ) throws SQLException {
        String query = "INSERT INTO " + ENTRY_TABLE +
                " (player_uuid, snapshot_index, entry_index, chance, server_name, received_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

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

    private List<ProbSnapshot> loadSnapshots(Connection connection, UUID uniqueId) throws SQLException {
        String query = "SELECT s.snapshot_index, s.created_at AS snapshot_created_at, " +
                "e.entry_index, e.chance, e.server_name, e.received_at " +
                "FROM " + SNAPSHOT_TABLE + " s " +
                "LEFT JOIN " + ENTRY_TABLE + " e ON e.player_uuid = s.player_uuid " +
                "AND e.snapshot_index = s.snapshot_index " +
                "WHERE s.player_uuid = ? " +
                "ORDER BY s.snapshot_index, e.entry_index";

        List<ProbSnapshot> snapshots = new ArrayList<>();
        int currentSnapshotIndex = -1;
        ProbSnapshot currentSnapshot = null;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uniqueId.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int snapshotIndex = resultSet.getInt("snapshot_index");
                    if (currentSnapshot == null || snapshotIndex != currentSnapshotIndex) {
                        currentSnapshot = new ProbSnapshot(
                                resultSet.getLong("snapshot_created_at"),
                                new ArrayList<>()
                        );
                        snapshots.add(currentSnapshot);
                        currentSnapshotIndex = snapshotIndex;
                    }

                    if (resultSet.getObject("entry_index") != null) {
                        currentSnapshot.getProbabilities().add(mapProbability(resultSet));
                    }
                }
            }
        }

        return snapshots;
    }

    private List<PlayerAIProbabilityData> mapAllPlayers(ResultSet resultSet) throws SQLException {
        List<PlayerAIProbabilityData> players = new ArrayList<>();
        UUID currentUuid = null;
        PlayerAIProbabilityData currentPlayer = null;
        ProbSnapshot currentSnapshot = null;
        int currentSnapshotIndex = -1;

        while (resultSet.next()) {
            UUID uuid = UUID.fromString(resultSet.getString("uuid"));
            if (!uuid.equals(currentUuid)) {
                currentPlayer = mapJoinedPlayer(resultSet, uuid);
                players.add(currentPlayer);
                currentUuid = uuid;
                currentSnapshot = null;
                currentSnapshotIndex = -1;
            }

            Object snapshotIndexValue = resultSet.getObject("snapshot_index");
            if (snapshotIndexValue == null) {
                continue;
            }

            int snapshotIndex = ((Number) snapshotIndexValue).intValue();
            if (currentSnapshot == null || snapshotIndex != currentSnapshotIndex) {
                currentSnapshot = new ProbSnapshot(
                        resultSet.getLong("snapshot_created_at"),
                        new ArrayList<>()
                );
                currentPlayer.getProbSnapshots().add(currentSnapshot);
                currentSnapshotIndex = snapshotIndex;
            }

            if (resultSet.getObject("entry_index") != null) {
                currentSnapshot.getProbabilities().add(mapProbability(resultSet));
            }
        }

        return players;
    }

    private PlayerAIProbabilityData mapPlayer(ResultSet resultSet) throws SQLException {
        UUID uuid = UUID.fromString(resultSet.getString("uuid"));
        String username = resolveUsername(uuid, resultSet.getString("username"));

        return new PlayerAIProbabilityData(
                new PlayerData(username, uuid),
                resultSet.getString("last_server"),
                new ArrayList<>(),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at")
        );
    }

    private PlayerAIProbabilityData mapJoinedPlayer(ResultSet resultSet, UUID uuid) throws SQLException {
        String username = resolveUsername(uuid, resultSet.getString("username"));

        return new PlayerAIProbabilityData(
                new PlayerData(username, uuid),
                resultSet.getString("last_server"),
                new ArrayList<>(),
                resultSet.getLong("player_created_at"),
                resultSet.getLong("updated_at")
        );
    }

    private Prob mapProbability(ResultSet resultSet) throws SQLException {
        return new Prob(
                resultSet.getDouble("chance"),
                resultSet.getString("server_name"),
                resultSet.getLong("received_at")
        );
    }

    private PlayerAIProbabilityData createDefaultPlayerData(UUID uniqueId, String playerName) {
        long now = System.currentTimeMillis();

        return new PlayerAIProbabilityData(
                new PlayerData(resolveUsername(uniqueId, playerName), uniqueId),
                Raveon.serverId(),
                new ArrayList<>(),
                now,
                now
        );
    }

    private boolean isValid(PlayerAIProbabilityData data) {
        return data != null
                && data.getPlayerData() != null
                && data.getPlayerData().getUniqueId() != null;
    }

    private void normalizePlayerData(PlayerAIProbabilityData data, UUID uniqueId, String playerName) {
        if (data.getPlayerData() == null) {
            data.setPlayerData(new PlayerData(resolveUsername(uniqueId, playerName), uniqueId));
        }

        if (data.getPlayerData().getUsername() == null || data.getPlayerData().getUsername().isBlank()) {
            data.setPlayerData(new PlayerData(resolveUsername(uniqueId, playerName), uniqueId));
        }

        if (data.getLastServerName() == null || data.getLastServerName().isBlank()) {
            data.setLastServerName(Raveon.serverId());
        }

        if (data.getProbSnapshots() == null) {
            data.setProbSnapshots(new ArrayList<>());
        }

        long now = System.currentTimeMillis();
        if (data.getCreatedAt() <= 0L) {
            data.setCreatedAt(now);
        }

        if (data.getUpdatedAt() <= 0L) {
            data.setUpdatedAt(now);
        }
    }

    private void deleteSnapshotData(Connection connection, UUID uniqueId) throws SQLException {
        executeDelete(connection, ENTRY_TABLE, "player_uuid", uniqueId.toString());
        executeDelete(connection, SNAPSHOT_TABLE, "player_uuid", uniqueId.toString());
    }

    private int deletePlayer(Connection connection, UUID uniqueId) throws SQLException {
        String query = "DELETE FROM " + PLAYER_TABLE + " WHERE uuid = ?";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uniqueId.toString());
            return statement.executeUpdate();
        }
    }

    private void executeDelete(
            Connection connection,
            String tableName,
            String columnName,
            String value
    ) throws SQLException {
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
            Raveon.INSTANCE.getLogger().log(level, message);
            return;
        }

        Raveon.INSTANCE.getLogger().log(level, message, throwable);
    }

    private record SnapshotPosition(
            int snapshotIndex,
            int entryIndex,
            boolean createSnapshot
    ) {
    }
}
