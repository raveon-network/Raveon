package ru.raveon.api.database;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@Data
@AllArgsConstructor
@RequiredArgsConstructor
public class DatabaseManager {
    private final Plugin plugin;

    private final String host;
    private final int port;

    private final String username;
    private final String password;
    private final String table;

    private final DatabaseType dataType;

    private String url;

    public static DatabaseManager fromSection(ConfigurationSection section, Plugin plugin) {
        return fromSection(section, plugin, null);
    }

    public static DatabaseManager fromSection(ConfigurationSection section, Plugin plugin, DatabaseManager defaultManager) {
        if (section == null) {
            return defaultManager;
        }

        String host = section.getString("host", "");
        int port = section.getInt("port", 3306);

        String username = section.getString("username", "");
        String password = section.getString("password", "");
        String table = section.getString("name", "");

        DatabaseType databaseType = DatabaseType.getByName(section.getString("data_type", "MYSQL"));
        if (databaseType == null) databaseType = DatabaseType.SQLITE;

        String url;
        if (databaseType == DatabaseType.SQLITE) {
            url = databaseType.generateUrl(host, port,
                    new File(plugin.getDataFolder(), table + ".db").getAbsolutePath());
        } else {
            url = databaseType.generateUrl(host, port, table);
        }

        return new DatabaseManager(plugin, host, port, username, password, table, databaseType, url);
    }

    public Connection getConnection() throws SQLException {
        if (url == null) {
            url = dataType.generateUrl(host, port, new File(plugin.getDataFolder(), table + ".db").getAbsolutePath());
        }

        return switch (dataType) {
            case MYSQL -> DriverManager.getConnection(url, username, password);
            case SQLITE -> DriverManager.getConnection(url);
        };
    }

    public String getSqlByDataType(String mysql, String sqlite) {
        if (dataType == DatabaseType.MYSQL) {
            return mysql;
        }

        return sqlite;
    }
}
