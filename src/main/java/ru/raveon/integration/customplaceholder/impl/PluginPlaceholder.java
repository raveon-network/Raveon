package ru.raveon.integration.customplaceholder.impl;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import ru.raveon.Raveon;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.config.MainConfigManager;
import ru.raveon.integration.customplaceholder.PlaceholderIntegration;
import ru.raveon.utils.StringColorize;

public class PluginPlaceholder implements PlaceholderIntegration {
    private Plugin plugin;

    @Override
    public void init(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPlaceholder(String path) {
        FileConfiguration settings = settings();
        return StringColorize.parse(
                settings == null ? null : settings.getString("placeholder." + path)
        );
    }

    @Override
    public String getPlaceholder(String path, String def) {
        FileConfiguration settings = settings();
        return StringColorize.parse(
                settings == null ? def : settings.getString("placeholder." + path, def)
        );
    }

    private FileConfiguration settings() {
        if (!(plugin instanceof Raveon raveon)) {
            return null;
        }

        MainConfigManager configManager = raveon.getMainConfigManager();
        if (configManager == null) {
            return null;
        }

        CustomConfig config = configManager.getCustomConfig("settings.yml");
        return config == null ? null : config.getConfig();
    }
}
