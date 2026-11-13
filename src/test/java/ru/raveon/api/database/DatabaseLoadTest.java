package ru.raveon.api.database;

import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Standalone stress tests; MySQL pool scheduling is emulated, not a MySQL performance benchmark. */
public final class DatabaseLoadTest {
    private static Path directory;
    private static Plugin plugin;
    private static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        directory = Files.createTempDirectory("raveon-db-load-");
        plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, parameters) -> switch (method.getName()) {
                    case "getLogger" -> Logger.getLogger("RaveonLoadTest");
                    case "getDataFolder" -> directory.toFile();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        report.add("# Database load tests: Raveon 2.1.0\n");
        report.add("Java: " + System.getProperty("java.version") + "; OS: " + System.getProperty("os.name") + ".\n");
        report.add("SQLite uses real file-backed SQL. MYSQL_POOL_EMULATED uses the production MySQL pool/executor configuration with a test JDBC driver backed by SQLite WAL. Its throughput is NOT a MySQL benchmark. No live Minecraft server or inference service is involved.\n");
        report.add("Closed-loop load: 16 client threads, 300 operations each; 60% reads, 30% committed two-table transactions, 10% rolled-back two-table transactions. Latency includes the application's queue. Startup and schema creation are excluded.\n");
        report.add("| Scenario | Operations | Ops/s | p50 ms | p95 ms | p99 ms | Validation |\n|---|---:|---:|---:|---:|---:|---|");
        try {
            mixedLoad(DatabaseType.SQLITE);
            mixedLoad(DatabaseType.MYSQL);
            overload(DatabaseType.SQLITE);
            overload(DatabaseType.MYSQL);
            gracefulShutdown();
            forcedShutdown();
            acquisitionTimeout();
            report.add("\nAll 7 stress scenarios passed. Rejections in overload, forced shutdown and acquisition timeout are expected and explicitly checked. Databases are disposable test files: " + directory + ".");
        } catch (Throwable error) {
            report.add("\nFAILED: " + error);
            throw error;
        } finally {
            Files.writeString(output.resolve("report.md"), String.join("\n", report));
        }
        System.out.println("DatabaseLoadTest: 7 stress scenarios passed; report=" + output.resolve("report.md"));
    }

    private static DatabaseManager database(DatabaseType type, String name) throws Exception {
        if (type == DatabaseType.SQLITE)
            return new DatabaseManager(plugin, "", 3306, "", "", name, type);
        Path file = directory.resolve(name + ".db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) { statement.execute("PRAGMA journal_mode=WAL"); }
        return new DatabaseManager(plugin, "unused", 3306, "test", "test", name, type,
                "jdbc:raveon-load:" + file);
    }

    private static final Driver EMULATED = new Driver() {
        public boolean acceptsURL(String url) { return url.startsWith("jdbc:raveon-load:"); }
        public Connection connect(String url, Properties properties) throws SQLException {
            if (!acceptsURL(url)) return null;
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + url.substring("jdbc:raveon-load:".length()));
            try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
            return connection;
        }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getLogger("RaveonLoadTest"); }
    };
    static {
        try { Class.forName("org.sqlite.JDBC"); DriverManager.registerDriver(EMULATED); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    private static HikariDataSource pool(DatabaseManager database) throws Exception {
        var field = DatabaseManager.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        return (HikariDataSource) field.get(database);
    }

    private static void mixedLoad(DatabaseType type) throws Exception {
        DatabaseManager database = database(type, "mixed-" + type);
        ExecutorService clients = Executors.newFixedThreadPool(16);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try {
            try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE counters(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
                statement.execute("CREATE TABLE ledger(id INTEGER PRIMARY KEY, owner INTEGER NOT NULL)");
                for (int i = 0; i < 16; i++) statement.executeUpdate("INSERT INTO counters VALUES(" + i + ", 0)");
            }
            List<Future<?>> futures = new ArrayList<>();
            for (int client = 0; client < 16; client++) {
                int owner = client;
                futures.add(clients.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 300; i++) {
                            int operation = i, id = owner * 300 + i;
                            long began = System.nanoTime();
                            database.submit(() -> {
                                try (Connection connection = database.getConnection()) {
                                    if (operation % 10 < 6) {
                                        try (PreparedStatement query = connection.prepareStatement("SELECT value FROM counters WHERE id=?")) {
                                            query.setInt(1, owner);
                                            try (ResultSet rows = query.executeQuery()) { check(rows.next() && rows.getInt(1) >= 0, "invalid read"); }
                                        }
                                    } else {
                                        connection.setAutoCommit(false);
                                        try (PreparedStatement update = connection.prepareStatement("UPDATE counters SET value=value+1 WHERE id=?");
                                             PreparedStatement insert = connection.prepareStatement("INSERT INTO ledger VALUES(?,?)")) {
                                            update.setInt(1, owner); check(update.executeUpdate() == 1, "missing counter");
                                            insert.setInt(1, id); insert.setInt(2, owner); insert.executeUpdate();
                                            if (operation % 10 == 9) connection.rollback(); else connection.commit();
                                        } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
                                    }
                                    return null;
                                } catch (SQLException error) { throw new CompletionException(error); }
                            }).get(30, TimeUnit.SECONDS);
                            latencies.add(System.nanoTime() - began);
                        }
                    } catch (Exception error) { throw new CompletionException(error); }
                }));
            }
            long began = System.nanoTime(); start.countDown();
            for (Future<?> future : futures) future.get(180, TimeUnit.SECONDS);
            double seconds = (System.nanoTime() - began) / 1e9;
            try (Connection connection = database.getConnection()) {
                check(query(connection, "SELECT COUNT(*) FROM ledger") == 1440, "commits lost or rollbacks persisted");
                check(query(connection, "SELECT SUM(value) FROM counters") == 1440, "counter/ledger transaction mismatch");
                check(query(connection, "SELECT COUNT(*) FROM counters WHERE value != 90") == 0, "per-client increments lost");
            }
            check(latencies.size() == 4800, "some operations did not finish");
            var mx = pool(database).getHikariPoolMXBean();
            check(mx.getActiveConnections() == 0 && mx.getThreadsAwaitingConnection() == 0, "connection leak");
            check(mx.getTotalConnections() <= (type == DatabaseType.SQLITE ? 1 : 4), "pool limit exceeded");
            Collections.sort(latencies);
            String name = type == DatabaseType.SQLITE ? "SQLITE" : "MYSQL_POOL_EMULATED";
            String row = String.format("| %s | 4800 | %.1f | %.3f | %.3f | %.3f | 1440 commits, 480 rollbacks, zero errors/leaks |",
                    name, 4800 / seconds, percentile(latencies, .50), percentile(latencies, .95), percentile(latencies, .99));
            report.add(row); System.out.println(row);
        } finally {
            start.countDown(); clients.shutdownNow(); database.shutdown().get(15, TimeUnit.SECONDS);
        }
    }

    private static void overload(DatabaseType type) throws Exception {
        DatabaseManager database = database(type, "overload-" + type);
        int workers = type == DatabaseType.SQLITE ? 1 : 4;
        CountDownLatch entered = new CountDownLatch(workers), release = new CountDownLatch(1);
        List<CompletableFuture<Integer>> accepted = new ArrayList<>();
        AtomicInteger executed = new AtomicInteger();
        try {
            for (int i = 0; i < workers; i++) accepted.add(database.submit(() -> {
                entered.countDown();
                try { check(release.await(4, TimeUnit.SECONDS), "release timeout"); }
                catch (InterruptedException error) { throw new CompletionException(error); }
                return 1;
            }));
            check(entered.await(4, TimeUnit.SECONDS), "workers not started");
            int rejected = 0;
            for (int i = 0; i < 2048; i++) {
                var future = database.submit(executed::incrementAndGet);
                if (future.isCompletedExceptionally()) {
                    try { future.join(); throw new AssertionError("expected rejection"); }
                    catch (CompletionException error) { check(error.getCause() instanceof RejectedExecutionException, "unexpected failure"); }
                    rejected++;
                } else accepted.add(future);
            }
            check(rejected == 1536 && accepted.size() == workers + 512, "queue must reject exactly above capacity");
            release.countDown(); CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            check(executed.get() == 512, "accepted queue work lost or executed twice");
            check(database.submit(() -> {
                try (Connection connection = database.getConnection()) { return query(connection, "SELECT 1"); }
                catch (SQLException error) { throw new CompletionException(error); }
            }).get(10, TimeUnit.SECONDS) == 1, "queue must recover after overload");
            report.add("| Overload " + type + " | 2048 queued attempts + " + workers + " running | — | — | — | — | 512 accepted, 1536 explicitly rejected; recovered |");
        } finally { release.countDown(); database.shutdown().get(15, TimeUnit.SECONDS); }
    }

    private static void gracefulShutdown() throws Exception {
        DatabaseManager database = database(DatabaseType.SQLITE, "graceful");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        tasks.add(database.submit(() -> {
            try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE drained(id INTEGER PRIMARY KEY)"); entered.countDown();
                check(release.await(4, TimeUnit.SECONDS), "release timeout"); return null;
            } catch (Exception error) { throw new CompletionException(error); }
        }));
        check(entered.await(4, TimeUnit.SECONDS), "worker not started");
        for (int i = 0; i < 160; i++) {
            int operation = i;
            tasks.add(database.submit(() -> {
                try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                    if (operation % 5 == 0) statement.executeUpdate("INSERT INTO drained VALUES(" + operation + ")");
                    else query(connection, "SELECT COUNT(*) FROM drained");
                    return null;
                } catch (SQLException error) { throw new CompletionException(error); }
            }));
        }
        var closing = database.shutdown(); release.countDown();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
        closing.get(15, TimeUnit.SECONDS);
        check(pool(database).isClosed(), "pool not closed");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("graceful.db"))) {
            check(query(connection, "SELECT COUNT(*) FROM drained") == 32, "shutdown lost accepted writes");
        }
        report.add("| Graceful shutdown | 161 accepted tasks | — | — | — | — | all finished, 32 writes persisted, pool closed |");
    }

    private static void forcedShutdown() throws Exception {
        DatabaseManager database = database(DatabaseType.SQLITE, "forced");
        try (Connection ignored = database.getConnection()) { }
        CountDownLatch entered = new CountDownLatch(1), never = new CountDownLatch(1);
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        tasks.add(database.submit(() -> {
            entered.countDown();
            try { never.await(); return null; }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CompletionException(error); }
        }));
        check(entered.await(3, TimeUnit.SECONDS), "worker not started");
        for (int i = 0; i < 200; i++) tasks.add(database.submit(() -> 1));
        var closing = database.shutdown();
        try { closing.get(15, TimeUnit.SECONDS); throw new AssertionError("shutdown deadline must fail explicitly"); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof RejectedExecutionException, "wrong shutdown failure"); }
        check(tasks.stream().allMatch(CompletableFuture::isDone), "shutdown left unresolved futures");
        check(pool(database).isClosed(), "forced shutdown leaked pool");
        report.add("| Forced shutdown | 201 tasks | — | — | — | — | 5 s deadline handled; all futures resolved, pool closed |");
    }

    private static void acquisitionTimeout() throws Exception {
        DatabaseManager database = database(DatabaseType.SQLITE, "timeout");
        try {
            try (Connection held = database.getConnection()) {
                var waiting = database.submit(() -> {
                    try (Connection ignored = database.getConnection()) { throw new AssertionError("pool should be exhausted"); }
                    catch (SQLException error) { return error; }
                });
                check(waiting.get(8, TimeUnit.SECONDS) instanceof SQLTransientConnectionException, "borrow must fail with timeout");
            }
            check(database.submit(() -> {
                try (Connection connection = database.getConnection()) { return query(connection, "SELECT 1"); }
                catch (SQLException error) { throw new CompletionException(error); }
            }).get(8, TimeUnit.SECONDS) == 1, "pool did not recover after exhaustion");
            report.add("| Connection exhaustion | 1 held + 1 waiter | — | — | — | — | waiter timed out at 3 s, next request recovered |");
        } finally { database.shutdown().get(15, TimeUnit.SECONDS); }
    }

    private static double percentile(List<Long> sorted, double percentile) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1)) / 1e6;
    }

    private static long query(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            check(rows.next(), "query returned no rows"); return rows.getLong(1);
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
