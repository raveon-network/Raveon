package ru.raveon.api.configuration;

import org.bukkit.plugin.Plugin;
import java.util.HashMap;
import java.util.Map;

public abstract class ConfigManager {
    protected final Plugin plugin;
    private final Map<String, CustomConfig> customConfigs;

    public ConfigManager(Plugin plugin) {
        customConfigs = new HashMap<>();
        this.plugin = plugin;

        loadConfigs();
        loadValues();
    }

    public abstract void loadConfigs();

    public abstract void loadValues();

    protected void addCustomConfig(CustomConfig config) {
        customConfigs.put(config.getName(), config);
        plugin.getLogger().info(String.format("CustomConfig '%s' successful initialization", config.getName()));
    }

    protected void removeCustomConfig(String name) {
        customConfigs.remove(name);
        plugin.getLogger().info(String.format("CustomConfig '%s' successful removed", name));
    }

    public CustomConfig getCustomConfig(String name) {
        return customConfigs.get(name);
    }

    public void reloadAll() {
        if (customConfigs.isEmpty()) return;
        customConfigs.values().forEach(CustomConfig::reloadConfig);
        loadValues();
    }

    public void saveAll() {
        if (customConfigs.isEmpty()) return;
        customConfigs.values().forEach(CustomConfig::saveConfig);
    }

}
