package ru.raveon.api.configuration;

import lombok.Getter;
import lombok.NonNull;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import ru.raveon.Raveon;
import ru.raveon.utils.StringColorize;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

@Getter
public class CustomConfig {
    private final File file;
    private final String name;
    private final boolean dataConfig;
    private FileConfiguration config;

    public CustomConfig(@NonNull String name, @NonNull Plugin plugin) {
        this(name, false, plugin);
    }

    public CustomConfig(@NonNull String name, boolean dataConfig, @NonNull Plugin plugin) {
        this(name, dataConfig, plugin, plugin.getDataFolder());
    }

    public CustomConfig(
            @NonNull String name,
            boolean dataConfig,
            @NonNull Plugin plugin,
            @NonNull File directory) {
        this.name = name;
        this.dataConfig = dataConfig;
        this.file = new File(directory, getFileName(name));

        initializeConfig(plugin);
    }

    private String getFileName(String name) {
        return name.endsWith(".yml") ? name : name + ".yml";
    }

    private void initializeConfig(Plugin plugin) {
        if (!file.exists()) {
            try {
                if (!dataConfig) {
                    plugin.saveResource(getFileName(name), false);
                } else {
                    file.getParentFile().mkdirs();
                    if (file.createNewFile()) {
                        Raveon.INSTANCE
                                .getLogger()
                                .log(Level.INFO, "Configuration {0} created", file.getName());
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                Raveon.INSTANCE.getLogger().log(Level.SEVERE, "Failed to load configuration " + name, e);
            }
        }
        this.config = readConfiguration();
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot save configuration " + name, error);
        }
    }

    private FileConfiguration readConfiguration() {
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(file);
            return candidate;
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException error) {
            throw new IllegalArgumentException("Cannot load configuration " + name, error);
        }
    }

    public boolean getBoolean(String path, boolean defaultValue) {
        Object value = config.get(path);
        if (value == null) return defaultValue;
        if (value instanceof Boolean bool) return bool;
        throw invalid(path, "must be a boolean");
    }

    public long getLong(String path, long defaultValue) {
        Object value = config.get(path);
        if (value == null) return defaultValue;
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        throw invalid(path, "must be an integer");
    }

    public int getInt(String path, int defaultValue) {
        long value = getLong(path, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw invalid(path, "is outside the integer range");
        return (int) value;
    }

    public int requireInt(String path, int defaultValue, int min, int max) {
        int value = getInt(path, defaultValue);
        if (value < min || value > max) throw invalid(path, "must be in " + min + ".." + max);
        return value;
    }

    public double getDouble(String path, double defaultValue) {
        Object value = config.get(path);
        if (value != null && !(value instanceof Number)) throw invalid(path, "must be a number");
        double result = value == null ? defaultValue : ((Number) value).doubleValue();
        if (!Double.isFinite(result)) throw invalid(path, "must be finite");
        return result;
    }

    public double requireDouble(String path, double defaultValue, double min, double max) {
        double value = getDouble(path, defaultValue);
        if (value < min || value > max) throw invalid(path, "must be in " + min + ".." + max);
        return value;
    }

    public String getString(String path, String defaultValue) {
        return StringColorize.parse(getRawString(path, defaultValue));
    }

    public String getRawString(String path, String defaultValue) {
        Object value = config.get(path);
        if (value == null) value = defaultValue;
        if (!(value instanceof String text)) throw invalid(path, "must be a string");
        return text;
    }

    private IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException(name + ": " + path + " " + reason);
    }
}
