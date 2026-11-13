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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

@Getter
public class CustomConfig {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");
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
        if (name.equals("settings.yml") && config.isSet("language")) {
            config.set("language", null);
            save();
        }
        loadTextDefaults(plugin);
    }

    private void loadTextDefaults(Plugin plugin) {
        boolean menu = name.equals("menu/history.yml") || name.equals("menu/players.yml");
        if (!menu && !name.equals("translation.yml") && !name.equals("features/datacollect.yml")) return;
        try (var input = plugin.getResource(getFileName(name))) {
            if (input == null) throw new IllegalArgumentException("Missing configuration resource " + name);
            YamlConfiguration defaults = new YamlConfiguration();
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) { defaults.load(reader); }
            if (menu) {
                boolean changed = false;
                for (String key : config.getKeys(true)) {
                    Object value = config.get(key);
                    if (value instanceof org.bukkit.configuration.ConfigurationSection || value == null
                            || !value.toString().contains("{lang:")) continue;
                    Object replacement = defaults.get(key);
                    if (replacement == null) throw new IllegalArgumentException("Unknown legacy menu text: " + key);
                    if (!changed) {
                        var original = file.toPath();
                        var backup = original.resolveSibling(file.getName() + ".before-english.bak");
                        if (!Files.exists(backup)) Files.copy(original, backup);
                    }
                    config.set(key, replacement);
                    changed = true;
                }
                if (changed) save();
            } else {
                // Missing messages in pre-existing feature configs use the English defaults.
                config.setDefaults(defaults);
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException error) {
            throw new IllegalArgumentException("Cannot load configuration defaults " + name, error);
        }
    }

    public String message(String path, Object... placeholders) {
        if (placeholders.length % 2 != 0) throw new IllegalArgumentException("Placeholders require name/value pairs");
        String text = StringColorize.parse(getRawString(path, null).replace("{prefix}", getRawString("prefix", "")));
        if (placeholders.length == 0) return text;
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < placeholders.length; i += 2)
            values.put(String.valueOf(placeholders[i]), java.util.Objects.toString(placeholders[i + 1], ""));
        return PLACEHOLDER.matcher(text).replaceAll(match ->
                Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group())));
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
