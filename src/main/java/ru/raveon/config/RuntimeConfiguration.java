package ru.raveon.config;

import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.service.LanguageService;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.config.anticheat.HologramConfigManager;
import ru.raveon.config.anticheat.PunishmentConfigManager;
import ru.raveon.config.datacollect.DataCollectConfigManager;

public record RuntimeConfiguration(MainConfigManager main, ChecksConfigManager checks,
        HologramConfigManager holograms, DataCollectConfigManager datasets,
        PunishmentConfigManager punishments) {
    public static RuntimeConfiguration load(Plugin plugin, RuntimeConfiguration previous) {
        CustomConfig settings = new CustomConfig("settings.yml", plugin);
        LanguageService language = LanguageService.load(plugin, settings.getRawString("language", "en"));
        ChecksConfigManager checks = new ChecksConfigManager(plugin);
        if (previous != null && !previous.checks().getAnalyzeServer().equals(checks.getAnalyzeServer()))
            throw new IllegalArgumentException("Changing analyze.server requires a restart");
        HologramConfigManager holograms = new HologramConfigManager(plugin, language);
        DataCollectConfigManager datasets = new DataCollectConfigManager(plugin);
        PunishmentConfigManager punishments = new PunishmentConfigManager(plugin, language);
        MainConfigManager main = new MainConfigManager(plugin, previous == null ? null : previous.main(), settings, language);
        return new RuntimeConfiguration(main, checks, holograms, datasets, punishments);
    }
}
