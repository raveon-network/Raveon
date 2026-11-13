package ru.raveon.config;

import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.config.anticheat.HologramConfigManager;
import ru.raveon.config.anticheat.PunishmentConfigManager;
import ru.raveon.config.datacollect.DataCollectConfigManager;
import ru.raveon.model.PluginModelProfile;

public record RuntimeConfiguration(MainConfigManager main, ChecksConfigManager checks,
        HologramConfigManager holograms, DataCollectConfigManager datasets,
        PunishmentConfigManager punishments) {
    public static RuntimeConfiguration load(Plugin plugin, RuntimeConfiguration previous) {
        return load(plugin, previous, previous == null ? PluginModelProfile.EMPTY : previous.checks().getRemoteProfile());
    }

    public static RuntimeConfiguration load(Plugin plugin, RuntimeConfiguration previous, PluginModelProfile profile) {
        CustomConfig settings = new CustomConfig("settings.yml", plugin);
        ChecksConfigManager checks = new ChecksConfigManager(plugin, profile);
        if (previous != null && !previous.checks().getAnalyzeServer().equals(checks.getAnalyzeServer()))
            throw new IllegalArgumentException("Changing analyze.server requires a restart");
        HologramConfigManager holograms = new HologramConfigManager(plugin);
        DataCollectConfigManager datasets = new DataCollectConfigManager(plugin);
        PunishmentConfigManager punishments = new PunishmentConfigManager(plugin, profile.punishments(), profile.models().keySet());
        MainConfigManager main = new MainConfigManager(plugin, previous == null ? null : previous.main(), settings);
        return new RuntimeConfiguration(main, checks, holograms, datasets, punishments);
    }
}
