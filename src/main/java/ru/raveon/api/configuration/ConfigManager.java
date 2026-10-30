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

    }

    protected final void initialize() {
        loadConfigs();
        loadValues();
    }

    public abstract void loadConfigs();

    public abstract void loadValues();

    protected void addCustomConfig(CustomConfig config) {
        customConfigs.put(config.getName(), config);

    }

    public CustomConfig getCustomConfig(String name) {
        return customConfigs.get(name);
    }

}
