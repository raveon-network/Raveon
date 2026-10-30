package ru.raveon.config;

import lombok.Getter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.api.configuration.RedisConfig;
import ru.raveon.api.database.DatabaseManager;
import ru.raveon.menu.history.HistoryMenuSettings;
import ru.raveon.menu.players.PlayersMenuSettings;
import ru.raveon.utils.StringColorize;
import ru.raveon.service.LanguageService;

import java.util.TreeMap;

@Getter
public class MainConfigManager extends ConfigManager {
    private MainConfigManager previous;
    private final CustomConfig settingsConfig;
    private final LanguageService languageService;
    private DatabaseManager databaseManager;
    private RedisConfig redisConfig;

    private boolean printToConsole;
    private int historyEntryPerPage;
    private int maxProbEntries;

    private int maxLocalEntries;
    private TreeMap<Double, String> aiChanceColors;

    private PlayersMenuSettings menuSettings;
    private HistoryMenuSettings historySettings;

    public MainConfigManager(Plugin plugin, MainConfigManager previous,
            CustomConfig settingsConfig, LanguageService languageService) {
        super(plugin);
        this.previous = previous;
        this.settingsConfig = settingsConfig;
        this.languageService = languageService;
        initialize();
        this.previous = null;
    }

    @Override
    public void loadConfigs() {
        addCustomConfig(settingsConfig);

        addCustomConfig(new CustomConfig("menu/players.yml", plugin));
        addCustomConfig(new CustomConfig("menu/history.yml", plugin));
    }

    @Override
    public void loadValues() {
        ConfigurationSection settings = getCustomConfig("settings.yml").getConfig();
        if (previous != null) {
            ConfigurationSection old = previous.getCustomConfig("settings.yml").getConfig();
            for (String section : java.util.List.of("database", "redis", "placeholder")) {
                ConfigurationSection before = old.getConfigurationSection(section);
                ConfigurationSection after = settings.getConfigurationSection(section);
                if (!java.util.Objects.equals(before == null ? null : before.getValues(true),
                        after == null ? null : after.getValues(true)))
                    throw new IllegalArgumentException("Changes to " + section + " require a restart");
            }
        }
        redisConfig = RedisConfig.fromSection(settings.getConfigurationSection("redis"));

        printToConsole = settings.getBoolean("print_to_console", false);
        historyEntryPerPage = getCustomConfig("settings.yml").requireInt("history_entries_per_page", 15, 1, 100);

        maxLocalEntries = getCustomConfig("settings.yml").requireInt("max_local_entries", 20, 1, 1024);
        maxProbEntries = getCustomConfig("settings.yml").requireInt("max_prob_entries", 21, 1, 4096);

        aiChanceColors = new TreeMap<>();
        ConfigurationSection colorSection = settings.getConfigurationSection("ai_colors");

        if (colorSection != null) {
            for (String key : colorSection.getKeys(false)) {
                double chance = Double.parseDouble(key.replace(",", "."));
                if (!Double.isFinite(chance) || chance < 0 || chance > 1)
                    throw new IllegalArgumentException("Invalid ai_colors probability");
                String raw = colorSection.getString(key);
                if (raw == null) throw new IllegalArgumentException("Missing ai_colors value");
                String color = StringColorize.parse(raw);
                aiChanceColors.put(chance, color);
            }
        }

        menuSettings =
                PlayersMenuSettings.fromSection(getCustomConfig("menu/players.yml").getConfig(), languageService);
        historySettings =
                HistoryMenuSettings.fromSection(getCustomConfig("menu/history.yml").getConfig(), languageService);
        databaseManager = previous == null
                ? DatabaseManager.fromSection(settings.getConfigurationSection("database"), plugin)
                : previous.getDatabaseManager();
    }

    public String getChanceColor(double chance) {
        var entry = aiChanceColors.floorEntry(chance);
        return entry != null ? entry.getValue() : "§f";
    }
}
