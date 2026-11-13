package ru.raveon.api.database;

import org.bukkit.plugin.Plugin;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Runs against the packaged plugin, with no unshaded HikariCP on the classpath. */
public final class ShadedDatabaseSmoke {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("raveon-shaded-database-");
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, parameters) -> switch (method.getName()) {
                    case "getLogger" -> Logger.getLogger("RaveonRegression");
                    case "getDataFolder" -> directory.toFile();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DatabaseManager database = new DatabaseManager(plugin, "", 3306, "", "", "smoke", DatabaseType.SQLITE);
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            if (!connection.getClass().getName().startsWith("ru.raveon.shade.hikari."))
                throw new AssertionError("packaged plugin must use isolated HikariCP classes");
            statement.executeUpdate("CREATE TABLE smoke(id INTEGER PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO smoke VALUES (1)");
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM smoke")) {
                if (!rows.next() || rows.getInt(1) != 1) throw new AssertionError("packaged pool query failed");
            }
            Object factory = Class.forName("ru.raveon.shade.slf4j.LoggerFactory")
                    .getMethod("getILoggerFactory").invoke(null);
            if (!factory.getClass().getName().equals("ru.raveon.shade.slf4j.jul.JDK14LoggerFactory"))
                throw new AssertionError("packaged pool logging must use its bundled JUL provider");
        } finally {
            database.shutdown().get(10, TimeUnit.SECONDS);
        }
        System.out.println("Packaged JAR: pooled SQL and isolated JUL logging passed");
    }
}
