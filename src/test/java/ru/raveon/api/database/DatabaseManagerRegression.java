package ru.raveon.api.database;

import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;
import org.sqlite.SQLiteConnection;
import ru.raveon.config.anticheat.PunishmentConfigManager.PunishGroupConfig;
import ru.raveon.database.modules.PlayerProbabilityDatabase;
import ru.raveon.database.modules.PunishmentDatabase;
import ru.raveon.database.modules.ViolationDatabase;
import ru.raveon.model.ModelId;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Exercises real pooled SQLite connections and the MySQL pool branch with a test JDBC driver. */
public final class DatabaseManagerRegression {
    private static Path directory;
    private static Plugin plugin;

    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory("raveon-hikari-regression-");
        plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, parameters) -> switch (method.getName()) {
                    case "getName", "toString" -> "RaveonRegression";
                    case "getLogger" -> Logger.getLogger("RaveonRegression");
                    case "getDataFolder" -> directory.toFile();
                    case "isEnabled" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == parameters[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        sqliteReusesConnectionsAndInitializesPragmas();
        transactionsAreIsolatedBetweenBorrowers();
        storageSchemasAndPunishmentsUseTheSharedPool();
        shutdownDrainsTasksAndClosesPool();
        shutdownBeforeFirstConnectionDoesNotStartPool();
        mysqlConfigurationAndConcurrentPool();
        System.out.println("DatabaseManager: 6 pooled database regression scenarios passed");
    }

    private static DatabaseManager sqlite(String name) {
        return new DatabaseManager(plugin, "", 3306, "", "", name, DatabaseType.SQLITE);
    }

    private static HikariDataSource pool(DatabaseManager database) throws Exception {
        var field = DatabaseManager.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        return (HikariDataSource) field.get(database);
    }

    private static void sqliteReusesConnectionsAndInitializesPragmas() throws Exception {
        DatabaseManager database = sqlite("reuse");
        try {
            check(pool(database).getHikariPoolMXBean() == null, "configuration must not start connections");
            SQLiteConnection physical;
            PreparedStatement statement;
            try (Connection connection = database.getConnection()) {
                physical = connection.unwrap(SQLiteConnection.class);
                check(query(connection, "PRAGMA foreign_keys") == 1, "foreign keys must be enabled");
                check(query(connection, "PRAGMA busy_timeout") == 5000, "busy timeout must be retained");
                statement = connection.prepareStatement("SELECT 1");
            }
            check(statement.isClosed(), "returning a connection must close outstanding statements");
            try (Connection connection = database.getConnection()) {
                check(connection.unwrap(SQLiteConnection.class) == physical, "SQLite must reuse its physical connection");
                check(!physical.isClosed(), "logical close must not close the physical connection");
            }
            check(pool(database).getMaximumPoolSize() == 1, "SQLite must have one connection");
        } finally {
            await(database.shutdown());
        }
    }

    private static void transactionsAreIsolatedBetweenBorrowers() throws Exception {
        DatabaseManager database = sqlite("transactions");
        try {
            try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE changes(id INTEGER PRIMARY KEY)");
                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO changes VALUES (1)");
                // Returning an uncommitted connection must roll this back.
            }
            try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                check(connection.getAutoCommit(), "next borrower must receive autoCommit=true");
                check(query(connection, "SELECT COUNT(*) FROM changes") == 0, "uncommitted data must not leak");
                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO changes VALUES (2)");
                connection.commit();
                statement.executeUpdate("INSERT INTO changes VALUES (3)");
                connection.rollback();
            }
            try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                check(connection.getAutoCommit(), "explicit transactions must also reset autoCommit");
                check(query(connection, "SELECT SUM(id) FROM changes") == 2, "commit and rollback must survive reuse");
                statement.execute("CREATE TABLE children(parent INTEGER REFERENCES changes(id))");
                try {
                    statement.executeUpdate("INSERT INTO children VALUES (999)");
                    throw new AssertionError("foreign key violation must fail");
                } catch (SQLException expected) {
                    // The same pool must remain usable after a failed statement.
                }
            }
            try (Connection connection = database.getConnection()) {
                check(query(connection, "SELECT COUNT(*) FROM children") == 0, "failed insert must not persist");
            }
        } finally {
            await(database.shutdown());
        }
    }

    private static void storageSchemasAndPunishmentsUseTheSharedPool() throws Exception {
        DatabaseManager database = sqlite("storage");
        try {
            var probabilities = new PlayerProbabilityDatabase(database);
            var violations = new ViolationDatabase(database);
            var punishments = new PunishmentDatabase(database, "regression", () -> 1000L);
            await(CompletableFuture.allOf(probabilities.createTable(), violations.createTable(), punishments.createTable()));
            UUID id = UUID.randomUUID();
            var group = new PunishGroupConfig("aim", List.of("aim"), Set.of(ModelId.PRO), List.of(), new TreeMap<>(), 60_000);
            check(await(punishments.record(id, ModelId.PRO, "AimAI", List.of(group), 1000)).get(0).total() == 1,
                    "first punishment transaction must persist");
            check(await(punishments.record(id, ModelId.PRO, "AimAI", List.of(group), 1000)).get(0).total() == 2,
                    "next punishment transaction must see committed data");
            check(await(violations.getLogCount(id)) == 0, "violation queries must share the pool");
            check(await(probabilities.resolvePlayerUuid("missing")) == null, "probability queries must share the pool");
            try (Connection connection = database.getConnection()) {
                check(connection.getAutoCommit(), "punishment transactions must not affect other storage");
                check(query(connection, "SELECT COUNT(*) FROM punishment_flags") == 2, "punishment rows must remain stored");
                check(pool(database).getHikariPoolMXBean().getTotalConnections() == 1, "all stores must share one SQLite connection");
            }
        } finally {
            await(database.shutdown());
        }
    }

    private static void shutdownDrainsTasksAndClosesPool() throws Exception {
        DatabaseManager database = sqlite("shutdown");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var first = database.submit(() -> {
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE drained(id INTEGER)");
                }
                return 1;
            } catch (Exception error) { throw new CompletionException(error); }
        });
        check(entered.await(3, TimeUnit.SECONDS), "worker must start");
        var second = database.submit(() -> {
            try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO drained VALUES (1)");
                return query(connection, "SELECT COUNT(*) FROM drained");
            } catch (SQLException error) { throw new CompletionException(error); }
        });
        var closing = database.shutdown();
        try {
            check(closing == database.shutdown(), "shutdown must be idempotent");
            check(!closing.isDone(), "shutdown must wait for accepted work");
            check(database.submit(() -> 3).isCompletedExceptionally(), "new tasks must be rejected during shutdown");
        } finally { release.countDown(); }
        check(await(first) == 1 && await(second) == 1, "accepted tasks must finish before pool closes");
        await(closing);
        check(pool(database).isClosed(), "shutdown future must complete after pool closes");
        try {
            database.getConnection();
            throw new AssertionError("closed pool must reject connections");
        } catch (SQLException expected) { }
    }

    private static void shutdownBeforeFirstConnectionDoesNotStartPool() throws Exception {
        DatabaseManager database = sqlite("unused");
        await(database.shutdown());
        check(pool(database).isClosed() && pool(database).getHikariPoolMXBean() == null,
                "unused pool must close without starting connections");
    }

    private static void mysqlConfigurationAndConcurrentPool() throws Exception {
        // A deterministic driver validates pool routing/credentials without using a real MySQL server.
        AtomicInteger opened = new AtomicInteger();
        Driver fake = new Driver() {
            public boolean acceptsURL(String url) { return url.startsWith("jdbc:raveon-regression:"); }
            public Connection connect(String url, Properties properties) throws SQLException {
                if (!acceptsURL(url)) return null;
                check("test-user".equals(properties.getProperty("user")), "MySQL username must reach driver");
                check("test-password".equals(properties.getProperty("password")), "MySQL password must reach driver");
                check("true".equals(properties.getProperty("tcpKeepAlive")), "MySQL keepalive must reach driver");
                opened.incrementAndGet();
                return DriverManager.getConnection("jdbc:sqlite::memory:");
            }
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public Logger getParentLogger() { return Logger.getLogger("RaveonRegression"); }
        };
        DriverManager.registerDriver(fake);
        DatabaseManager database = new DatabaseManager(plugin, "unused", 3306, "test-user", "test-password",
                "mysql-test", DatabaseType.MYSQL, "jdbc:raveon-regression:pool");
        ExecutorService borrowers = Executors.newFixedThreadPool(4);
        CountDownLatch borrowed = new CountDownLatch(4), release = new CountDownLatch(1);
        try {
            check(pool(database).getHikariPoolMXBean() == null, "MySQL must also start lazily");
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) tasks.add(borrowers.submit(() -> {
                try (Connection connection = database.getConnection()) {
                    borrowed.countDown();
                    check(release.await(8, TimeUnit.SECONDS), "release timeout");
                    check(query(connection, "SELECT 1") == 1, "pooled connection must remain usable");
                } catch (Exception error) { throw new CompletionException(error); }
            }));
            check(borrowed.await(8, TimeUnit.SECONDS), "four MySQL connections must be usable concurrently");
            check(pool(database).getHikariPoolMXBean().getTotalConnections() == 4, "MySQL pool must be bounded at four");
            release.countDown();
            for (Future<?> task : tasks) task.get(8, TimeUnit.SECONDS);
            try (Connection ignored = database.getConnection()) {
                check(opened.get() == 4, "subsequent operation must reuse an existing connection");
            }
        } finally {
            release.countDown(); borrowers.shutdownNow();
            await(database.shutdown()); DriverManager.deregisterDriver(fake);
        }
    }

    private static long query(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("missing row: " + sql);
            return rows.getLong(1);
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(12, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
