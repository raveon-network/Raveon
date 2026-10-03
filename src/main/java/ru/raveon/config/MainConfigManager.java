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

import java.util.TreeMap;

@Getter
public class MainConfigManager extends ConfigManager {
    private DatabaseManager databaseManager;
    private RedisConfig redisConfig;

    private boolean printToConsole;
    private int historyEntryPerPage;
    private int maxProbEntries;

    private String prefix;

    private String alertsEnabledMessage;
    private String alertsDisableMessage;
    private String hologramsEnabledMessage;
    private String hologramsDisableMessage;

    private String aiAlertMessage;

    private String historyHeaderMessage;
    private String historyEntryMessage;
    private String historyOnlyPlayerMessage;
    private String historyNoDataMessage;

    private String reloadingMessage;
    private String reloadedMessage;

    private String monitorActionBarFormat;
    private String monitorWaitingFormat;
    private String monitorChatFormat;
    private String monitorChatAllName;
    private String monitorChatEnabledMessage;
    private String monitorChatSwitchedMessage;
    private String monitorChatDisabledMessage;
    private String monitorUsageMessage;
    private String monitorTrendUpFormat;
    private String monitorTrendDownFormat;
    private String monitorTrendEqualFormat;

    private String monitorOnlyPlayerMessage;
    private String monitorEnabledMessage;
    private String monitorDisabledMessage;
    private String monitorNotRunningMessage;
    private String monitorSwitchedMessage;
    private String monitorPlayerNotFoundMessage;
    private String monitorTargetLeftMessage;

    private int maxLocalEntries;
    private TreeMap<Double, String> aiChanceColors;

    private PlayersMenuSettings menuSettings;
    private HistoryMenuSettings historySettings;


    public MainConfigManager(Plugin plugin) {
        super(plugin);
    }

    @Override
    public void loadConfigs() {
        addCustomConfig(new CustomConfig("settings.yml", plugin));
        addCustomConfig(new CustomConfig("translation.yml", plugin));

        addCustomConfig(new CustomConfig("menu/players.yml", plugin));
        addCustomConfig(new CustomConfig("menu/history.yml", plugin));
    }

    @Override
    public void loadValues() {
        ConfigurationSection settings = getCustomConfig("settings.yml").getConfig();
        databaseManager = DatabaseManager.fromSection(settings.getConfigurationSection("database"), plugin);
        redisConfig = RedisConfig.fromSection(settings.getConfigurationSection("redis"));

        printToConsole = settings.getBoolean("print_to_console", false);
        historyEntryPerPage = settings.getInt("history_entries_per_page", 15);

        maxLocalEntries = settings.getInt("max_local_entries", 20);
        maxProbEntries = settings.getInt("max_prob_entries", 21);

        aiChanceColors = new TreeMap<>();
        ConfigurationSection colorSection = settings.getConfigurationSection("ai_colors");

        if (colorSection != null) {
            for (String key : colorSection.getKeys(false)) {
                double chance = Double.parseDouble(key.replace(",", "."));
                String color = StringColorize.parse(colorSection.getString(key, null));
                aiChanceColors.put(chance, color);
            }
        }

        CustomConfig messagesConfig = getCustomConfig("translation.yml");
        prefix = messagesConfig.getString("prefix", null);

        alertsEnabledMessage = messagesConfig.getString("alerts-enabled", null).replace("{prefix}", prefix);
        alertsDisableMessage = messagesConfig.getString("alerts-disabled", null).replace("{prefix}", prefix);
        hologramsEnabledMessage = messagesConfig.getString("holograms-enabled", null).replace("{prefix}", prefix);
        hologramsDisableMessage = messagesConfig.getString("holograms-disabled", null).replace("{prefix}", prefix);

        aiAlertMessage = messagesConfig.getString("ai-alert", null).replace("{prefix}", prefix);


        historyHeaderMessage = messagesConfig.getString("history-header", null).replace("{prefix}", prefix);
        historyEntryMessage = messagesConfig.getString("history-entry", null).replace("{prefix}", prefix);
        historyOnlyPlayerMessage = messagesConfig.getString("history-only-player", null).replace("{prefix}", prefix);
        historyNoDataMessage = messagesConfig.getString("history-no-data", null).replace("{prefix}", prefix);

        reloadingMessage = messagesConfig.getString("reloading", null).replace("{prefix}", prefix);
        reloadedMessage = messagesConfig.getString("reloaded", null).replace("{prefix}", prefix);

        monitorActionBarFormat = messagesConfig.getString("monitor.action_bar.format", null).replace("{prefix}", prefix);
        monitorWaitingFormat = messagesConfig.getString("monitor.action_bar.waiting", null).replace("{prefix}", prefix);
        monitorTrendUpFormat = messagesConfig.getString("monitor.action_bar.trend.up", null).replace("{prefix}", prefix);
        monitorTrendDownFormat = messagesConfig.getString("monitor.action_bar.trend.down", null).replace("{prefix}", prefix);
        monitorTrendEqualFormat = messagesConfig.getString("monitor.action_bar.trend.equal", null).replace("{prefix}", prefix);

        monitorChatFormat = messagesConfig.getString("monitor.chat.format", null).replace("{prefix}", prefix);
        monitorChatAllName = messagesConfig.getString("monitor.chat.all_name", null);
        monitorChatEnabledMessage = messagesConfig.getString("monitor.chat.enabled", null).replace("{prefix}", prefix);
        monitorChatSwitchedMessage = messagesConfig.getString("monitor.chat.switched", null).replace("{prefix}", prefix);
        monitorChatDisabledMessage = messagesConfig.getString("monitor.chat.disabled", null).replace("{prefix}", prefix);
        monitorUsageMessage = messagesConfig.getString("monitor.usage", null).replace("{prefix}", prefix);
        monitorOnlyPlayerMessage = messagesConfig.getString("monitor.only_player", null).replace("{prefix}", prefix);
        monitorEnabledMessage = messagesConfig.getString("monitor.enabled", null).replace("{prefix}", prefix);
        monitorDisabledMessage = messagesConfig.getString("monitor.disabled", null).replace("{prefix}", prefix);
        monitorNotRunningMessage = messagesConfig.getString("monitor.not_running", null).replace("{prefix}", prefix);
        monitorSwitchedMessage = messagesConfig.getString("monitor.switched", null).replace("{prefix}", prefix);
        monitorPlayerNotFoundMessage = messagesConfig.getString("monitor.player_not_found", null).replace("{prefix}", prefix);
        monitorTargetLeftMessage = messagesConfig.getString("monitor.target_left", null).replace("{prefix}", prefix);

        menuSettings = PlayersMenuSettings.fromSection(getCustomConfig("menu/players.yml").getConfig());
        historySettings = HistoryMenuSettings.fromSection(getCustomConfig("menu/history.yml").getConfig());
    }

    public String getChanceColor(double chance) {
        var entry = aiChanceColors.floorEntry(chance);
        return entry != null ? entry.getValue() : "§f";
    }

    public String getChanceString(double chance) {
        String color = getChanceColor(chance);
        return "%s%.4f".formatted(color, chance);
    }

    public String getPercentString(double percent) {
        String color = getChanceColor(percent);
        return "%s%.0f".formatted(color, percent * 100);
    }
}
