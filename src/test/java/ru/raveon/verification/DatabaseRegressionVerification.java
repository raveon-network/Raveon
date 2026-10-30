package ru.raveon.verification;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.plugin.Plugin;
import ru.raveon.Raveon;
import ru.raveon.model.ModelId;
import ru.raveon.api.database.DatabaseManager;
import ru.raveon.api.database.DatabaseType;
import ru.raveon.database.modules.PlayerProbabilityDatabase;
import ru.raveon.database.modules.ViolationDatabase;
import ru.raveon.integration.customplaceholder.PlaceholderIntegration;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class DatabaseRegressionVerification {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000123");

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("raveon-database-verification-");
        Plugin plugin = plugin(directory);
        installServerIdentity();
        verifyBoundedQueue(plugin);
        verifyForcedShutdown(plugin);
        verifyReadinessAdmission(plugin, directory);
        verifyInitializationRecovery(plugin, directory);
        verifySqlite(plugin, directory);
        verifySummaryQueryCount(plugin, directory);
        System.out.println("Real SQLite readiness, failure preservation, bounded reads, retention and shutdown checks passed");
    }

    private static void verifyBoundedQueue(Plugin plugin) throws Exception {
        DatabaseManager manager = new DatabaseManager(plugin, "", 3306, "", "", "queue", DatabaseType.SQLITE);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> first = manager.submit(() -> {
            started.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Worker fixture timed out");
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
        });
        require(started.await(2, TimeUnit.SECONDS), "Worker did not start");
        List<CompletableFuture<Integer>> queued = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            int value = i;
            queued.add(manager.submit(() -> value));
        }
        CompletableFuture<Integer> overflow = manager.submit(() -> 999);
        require(overflow.isCompletedExceptionally(), "Queue accepted work beyond its capacity");
        CompletableFuture<Void> close = manager.shutdown();
        require(!close.isDone(), "Shutdown completed while accepted write was still running");
        release.countDown();
        close.get(3, TimeUnit.SECONDS);
        require(first.get(), "First accepted task was lost");
        for (int i = 0; i < queued.size(); i++) require(queued.get(i).get() == i, "Drain lost a queued task");
        require(manager.submit(() -> true).isCompletedExceptionally(), "Closed storage accepted new work");
    }

    private static void verifyForcedShutdown(Plugin plugin) throws Exception {
        DatabaseManager manager = new DatabaseManager(plugin, "", 3306, "", "", "shutdown", DatabaseType.SQLITE);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean ranQueued = new AtomicBoolean();
        CompletableFuture<Boolean> running = manager.submit(() -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                }
            }
            return true;
        });
        try {
            require(started.await(2, TimeUnit.SECONDS), "Shutdown worker fixture did not start");
            CompletableFuture<Boolean> queued = manager.submit(() -> {
                ranQueued.set(true);
                return true;
            });
            CompletableFuture<Void> closing = manager.shutdown();
            require(closing.handle((value, error) -> error != null).get(7, TimeUnit.SECONDS),
                    "Forced shutdown did not report its deadline");
            require(manager.shutdown() == closing, "Shutdown is not idempotent");
            require(running.isCompletedExceptionally() && queued.isCompletedExceptionally(),
                    "Forced shutdown left unfinished futures");
            require(!ranQueued.get(), "Forced shutdown executed a queued task");
            Field pending = DatabaseManager.class.getDeclaredField("pending");
            pending.setAccessible(true);
            require(((java.util.Set<?>) pending.get(manager)).isEmpty(),
                    "Forced shutdown retains cancelled task payloads");
        } finally {
            release.countDown();
        }
    }

    private static void verifyReadinessAdmission(Plugin plugin, Path directory) throws Exception {
        CountDownLatch ddlStarted = new CountDownLatch(1);
        CountDownLatch releaseDdl = new CountDownLatch(1);
        AtomicBoolean firstConnection = new AtomicBoolean(true);
        DatabaseManager manager = new DatabaseManager(plugin, "", 3306, "", "", "readiness",
                DatabaseType.SQLITE, "jdbc:sqlite:" + directory.resolve("readiness.db")) {
            @Override
            public Connection getConnection() throws SQLException {
                if (firstConnection.compareAndSet(true, false)) {
                    ddlStarted.countDown();
                    try {
                        if (!releaseDdl.await(15, TimeUnit.SECONDS)) throw new SQLException("DDL fixture timed out");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new SQLException(exception);
                    }
                }
                return super.getConnection();
            }
        };
        PlayerProbabilityDatabase storage = new PlayerProbabilityDatabase(manager);
        storage.createTable();
        require(ddlStarted.await(2, TimeUnit.SECONDS), "DDL did not start");
        List<CompletableFuture<?>> reads = new ArrayList<>();
        try {
            for (int i = 0; i < 600; i++) reads.add(storage.getPlayerHistory(PLAYER, 0, 1, ModelId.PRO));
            require(reads.stream().filter(CompletableFuture::isCompletedExceptionally).count() >= 88,
                    "Schema readiness kept an unbounded backlog outside the SQL queue");
        } finally {
            releaseDdl.countDown();
        }
        CompletableFuture.allOf(reads.stream().map(future -> future.handle((value, error) -> null))
                .toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        storage.shutdown().get(6, TimeUnit.SECONDS);
    }

    private static void verifyInitializationRecovery(Plugin plugin, Path directory) throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var recovered = new AtomicBoolean();
            DatabaseManager manager = new DatabaseManager(plugin, "", 3306, "", "", "recovery",
                    DatabaseType.SQLITE, "jdbc:sqlite:" + directory.resolve("recovery-" + kind + ".db")) {
                @Override
                public Connection getConnection() throws SQLException {
                    attempts.incrementAndGet();
                    if (!recovered.get()) throw new SQLException("Injected initialization outage");
                    return super.getConnection();
                }
            };
            Object storage = switch (kind) {
                case 0 -> new PlayerProbabilityDatabase(manager);
                case 1 -> new ViolationDatabase(manager);
                default -> new ru.raveon.database.modules.PunishmentDatabase(manager, "test");
            };
            Method create = storage.getClass().getMethod("createTable");
            try {
                requireFailure((CompletableFuture<?>) create.invoke(storage));
                recovered.set(true);
                for (int i = 0; i < 100; i++)
                    requireFailure((CompletableFuture<?>) create.invoke(storage));
                require(attempts.get() == 1, "Initialization retries ignored their cooldown");
                Field initialization = storage.getClass().getDeclaredField("initialization");
                initialization.setAccessible(true);
                Object state = initialization.get(storage);
                Field retryAfter = state.getClass().getDeclaredField("retryAfter");
                retryAfter.setAccessible(true);
                retryAfter.setLong(state, System.nanoTime() - 1);
                var retries = new ArrayList<CompletableFuture<?>>();
                for (int i = 0; i < 100; i++) retries.add((CompletableFuture<?>) create.invoke(storage));
                CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
                require(attempts.get() == 2, "Concurrent callers caused duplicate table initialization");
                if (storage instanceof PlayerProbabilityDatabase probabilities) {
                    probabilities.ensurePlayer(PLAYER, "Recovered").get(5, TimeUnit.SECONDS);
                    require(probabilities.resolvePlayerUuid("Recovered").get().equals(PLAYER),
                            "Probability storage did not recover");
                } else if (storage instanceof ViolationDatabase violations) {
                    violations.logAlert(PLAYER, "test", "recovered", "AimAI", 1).get(5, TimeUnit.SECONDS);
                    require(violations.getLogCount(PLAYER).get() == 1, "Violation storage did not recover");
                } else {
                    var group = new ru.raveon.config.anticheat.PunishmentConfigManager.PunishGroupConfig(
                            "aim", List.of("AimAI"), java.util.Set.of(ru.raveon.model.ModelId.PRO),
                            List.of(), new java.util.TreeMap<>(), 60_000);
                    var result = ((ru.raveon.database.modules.PunishmentDatabase) storage).record(
                            PLAYER, ru.raveon.model.ModelId.PRO, "AimAI", List.of(group),
                            System.currentTimeMillis()).get(5, TimeUnit.SECONDS);
                    require(result.size() == 1 && result.get(0).total() == 1,
                            "Punishment storage did not recover");
                }
            } finally {
                manager.shutdown().get(6, TimeUnit.SECONDS);
            }
        }
    }

    private static void verifySqlite(Plugin plugin, Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("history.db");
        AtomicBoolean failRead = new AtomicBoolean();
        DatabaseManager manager = new DatabaseManager(plugin, "", 3306, "", "", "history", DatabaseType.SQLITE, url) {
            @Override
            public Connection getConnection() throws SQLException {
                if (failRead.compareAndSet(true, false)) throw new SQLException("Injected connection outage");
                return super.getConnection();
            }
        };
        PlayerProbabilityDatabase probabilities = new PlayerProbabilityDatabase(manager);
        ViolationDatabase violations = new ViolationDatabase(manager);
        require(probabilities.getPlayerDataByUUID(PLAYER, ModelId.PRO).get(5, TimeUnit.SECONDS) == null,
                "Missing player should be absent after schema readiness");
        require(violations.getLogCount(PLAYER).get(5, TimeUnit.SECONDS) == 0,
                "Violations schema was not ready before read");
        seed(manager);
        failRead.set(true);
        requireFailure(probabilities.getOrCreatePlayerData(PLAYER, "Tester", ModelId.PRO));
        require(count(manager, "player_probability_entries") == 6, "Read failure deleted saved history");
        probabilities.ensurePlayer(PLAYER, "Renamed").get(3, TimeUnit.SECONDS);
        require(count(manager, "player_probability_entries") == 6, "Player initialization replaced history");
        require(probabilities.resolvePlayerUuid("Renamed").get().equals(PLAYER), "Name lookup failed");

        var page = probabilities.getPlayerHistory(PLAYER, 1, 1, ModelId.PRO).get();
        require(page.page() == 1 && page.totalSnapshots() == 3, "Wrong SQL page metadata");
        require(page.data().getProbSnapshots().size() == 1, "History loaded more than one requested snapshot");
        require(page.data().getProbabilities().equals(List.of(0.2, 0.3)), "Wrong history page or entry order");
        var summaries = probabilities.getPlayersData(List.of(PLAYER), 3, ModelId.PRO).get();
        require(summaries.size() == 1, "Summary selected unrelated players");
        require(summaries.get(0).getProbabilities().equals(List.of(0.3, 0.4, 0.5)),
                "Recent summary is not bounded or chronological");
        requireFailure(probabilities.getPlayersData(List.of(PLAYER), 0, ModelId.PRO));
        requireFailure(violations.getViolations(PLAYER, 0, -1));

        try (Connection connection = manager.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO violations(player_uuid,timestamp) VALUES ('" + PLAYER + "',1)");
            statement.executeUpdate("INSERT INTO violations(player_uuid,timestamp) VALUES ('" + PLAYER + "',2)");
            try (var rows = statement.executeQuery("SELECT COUNT(DISTINCT id),MIN(id),MAX(id) FROM violations")) {
                require(rows.next() && rows.getInt(1) == 2 && rows.getLong(2) > 0 && rows.getLong(3) > rows.getLong(2),
                        "SQLite violation primary key does not auto-increment");
            }
        }
        probabilities.shutdown().get(6, TimeUnit.SECONDS);

        MemoryConfiguration configuration = new MemoryConfiguration();
        configuration.set("data_type", "SQLITE");
        configuration.set("name", "history");
        configuration.set("retention_days", 1);
        DatabaseManager retained = DatabaseManager.fromSection(configuration, plugin);
        PlayerProbabilityDatabase retainedStore = new PlayerProbabilityDatabase(retained);
        retainedStore.createTable().get();
        Method append = PlayerProbabilityDatabase.class.getDeclaredMethod(
                "addProbability", UUID.class, String.class, double.class, String.class, long.class, int.class, ModelId.class);
        append.setAccessible(true);
        require((boolean) append.invoke(retainedStore, PLAYER, "Renamed", 0.9, "test",
                        System.currentTimeMillis(), 10, ModelId.PRO), "Retention write failed");
        require(count(retained, "player_probability_entries") == 1, "Opt-in retention kept expired entries or deleted fresh entry");
        retainedStore.shutdown().get(6, TimeUnit.SECONDS);
    }

    private static void verifySummaryQueryCount(Plugin plugin, Path directory) throws Exception {
        var queries = new java.util.concurrent.atomic.AtomicInteger();
        DatabaseManager manager = new DatabaseManager(plugin, "", 3306, "", "", "summary",
                DatabaseType.SQLITE, "jdbc:sqlite:" + directory.resolve("summary.db")) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            try {
                                Object value = method.invoke(connection, args);
                                if (method.getName().equals("prepareStatement")) {
                                    var statement = (java.sql.PreparedStatement) value;
                                    return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),
                                            new Class<?>[] {java.sql.PreparedStatement.class}, (p, m, a) -> {
                                                if (m.getName().equals("executeQuery")) queries.incrementAndGet();
                                                try { return m.invoke(statement, a); }
                                                catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                                            });
                                }
                                return value;
                            } catch (java.lang.reflect.InvocationTargetException error) {
                                throw error.getCause();
                            }
                        });
            }
        };
        var storage = new PlayerProbabilityDatabase(manager);
        var ids = new ArrayList<UUID>();
        try {
            storage.createTable().get(5, TimeUnit.SECONDS);
            try (Connection connection = manager.getConnection()) {
                connection.setAutoCommit(false);
                try (var player = connection.prepareStatement("INSERT INTO player_probabilities VALUES (?, 'Player', 'test', 1, 1)");
                        var snapshot = connection.prepareStatement("INSERT INTO player_probability_snapshots VALUES (?, 0, 1)");
                        var entry = connection.prepareStatement("INSERT INTO player_probability_entries VALUES (?, 0, ?, ?, 'test', ?)");
                        var ultraSnapshot = connection.prepareStatement("INSERT INTO ultra_probability_snapshots VALUES (?, 0, 1)");
                        var ultraEntry = connection.prepareStatement("INSERT INTO ultra_probability_entries VALUES (?, 0, 0, 0.9, 'ultra', 10)")) {
                    for (int i = 0; i < 1000; i++) {
                        UUID id = new UUID(1, i);
                        ids.add(id);
                        for (var statement : List.of(player, snapshot, ultraSnapshot, ultraEntry)) {
                            statement.setString(1, id.toString());
                            statement.addBatch();
                        }
                        for (int j = 1; j <= 3; j++) {
                            entry.setString(1, id.toString());
                            entry.setInt(2, j);
                            entry.setDouble(3, j / 10.0);
                            entry.setLong(4, j);
                            entry.addBatch();
                        }
                    }
                    player.executeBatch();
                    snapshot.executeBatch();
                    ultraSnapshot.executeBatch();
                    entry.executeBatch();
                    ultraEntry.executeBatch();
                }
                connection.commit();
            }
            for (ModelId model : ModelId.values()) {
                queries.set(0);
                long started = System.nanoTime();
                var results = storage.getPlayersData(ids, 2, model).get(10, TimeUnit.SECONDS);
                require(results.size() == 1000 && queries.get() == 8,
                        "Summary loading still performs per-player SQL round trips: " + queries.get());
                for (var data : results) {
                    require(data.getModel() == model, "Summary mixed model labels");
                    require(data.getProbabilities().equals(model == ModelId.PRO ? List.of(.2, .3) : List.of(.9)),
                            "Summary recent-entry limit or chronological order is wrong");
                }
                System.out.println("SUMMARY model=" + model + " players=1000 SELECTs=" + queries.get()
                        + " elapsed_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        } finally {
            manager.shutdown().get(6, TimeUnit.SECONDS);
        }
    }

    private static void seed(DatabaseManager manager) throws SQLException {
        try (Connection connection = manager.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO player_probabilities VALUES ('" + PLAYER + "','Tester','test',1,1)");
            for (int snapshot = 0; snapshot < 3; snapshot++) {
                statement.executeUpdate("INSERT INTO player_probability_snapshots VALUES ('" + PLAYER + "'," + snapshot + ",1)");
                for (int entry = 0; entry < 2; entry++)
                    statement.executeUpdate("INSERT INTO player_probability_entries VALUES ('" + PLAYER + "',"
                            + snapshot + "," + entry + "," + (snapshot * 2 + entry) / 10.0 + ",'test',1)");
            }
        }
    }

    private static int count(DatabaseManager manager, String table) throws SQLException {
        try (Connection connection = manager.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            require(rows.next(), "Missing count row");
            return rows.getInt(1);
        }
    }

    private static void requireFailure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            throw new AssertionError("Failed operation was reported as successful");
        } catch (java.util.concurrent.ExecutionException expected) {
            require(expected.getCause() != null, "Failure lost its cause");
        }
    }

    private static Plugin plugin(Path directory) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> Logger.getLogger("RaveonDatabaseVerification");
                    case "getDataFolder" -> directory.toFile();
                    case "isEnabled" -> true;
                    case "getName" -> "RaveonDatabaseVerification";
                    case "toString" -> "RaveonDatabaseVerification";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void installServerIdentity() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Raveon plugin = (Raveon) unsafe.allocateInstance(Raveon.class);
        Field placeholder = Raveon.class.getDeclaredField("placeholderIntegration");
        placeholder.setAccessible(true);
        placeholder.set(plugin, new PlaceholderIntegration() {
            public void init(Plugin ignored) {}
            public String getPlaceholder(String path) { return "test"; }
            public String getPlaceholder(String path, String fallback) { return "test"; }
        });
        Raveon.INSTANCE = plugin;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
