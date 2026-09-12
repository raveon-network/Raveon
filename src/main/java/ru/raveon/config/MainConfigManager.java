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
    private String verboseEnabledMessage;
    private String verboseDisableMessage;
    private String hologramsEnabledMessage;
    private String hologramsDisableMessage;

    private String aiAlertMessage;
    private String aiVerboseMessage;
    private String alertMessage;
    private String verboseMessage;

    private String historyHeaderMessage;
    private String historyEntryMessage;
    private String historyOnlyPlayerMessage;
    private String historyNoDataMessage;

    private String reloadingMessage;
    private String reloadedMessage;

    private String monitorActionBarFormat;
    private String monitorWaitingFormat;
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
                String color = StringColorize.parse(colorSection.getString(key, "§f"));
                aiChanceColors.put(chance, color);
            }
        }

        CustomConfig messagesConfig = getCustomConfig("translation.yml");
        prefix = messagesConfig.getString("prefix", "&7[&cRaveon&7]&r");

        alertsEnabledMessage = messagesConfig.getString("alerts-enabled", "{prefix} &fОповещения включены!").replace("{prefix}", prefix);
        alertsDisableMessage = messagesConfig.getString("alerts-disabled", "{prefix} &fОповещения отключены!").replace("{prefix}", prefix);
        verboseEnabledMessage = messagesConfig.getString("verbose-enabled", "{prefix} &fПодробная информация включена!").replace("{prefix}", prefix);
        verboseDisableMessage = messagesConfig.getString("verbose-disabled", "{prefix} &fПодробная информация отключена!").replace("{prefix}", prefix);
        hologramsEnabledMessage = messagesConfig.getString("holograms-enabled", "{prefix} &fПоказ голограмм над игроком включен!").replace("{prefix}", prefix);
        hologramsDisableMessage = messagesConfig.getString("holograms-disabled", "{prefix} &fПоказ голограмм над игроком отключен!").replace("{prefix}", prefix);

        aiAlertMessage = messagesConfig.getString("ai-alert", "{prefix} &fИгрок &c{player}&f провалил проверку &cAimAI &7[&cx{vl}&7] &7{probability}").replace("{prefix}", prefix);
        aiVerboseMessage = messagesConfig.getString("ai-verbose", "{prefix} &fИгрок &c{player} &fрезультат: &7{probability}").replace("{prefix}", prefix);

        alertMessage = messagesConfig.getString("alert", "{prefix} &fИгрок &x&F&B&0&8&0&8{player} &fпровалил &7&x&F&B&0&8&0&8{check_name} &7[&fx&x&F&B&0&8&0&8{vl}&7] &7{verbose}").replace("{prefix}", prefix);
        verboseMessage = messagesConfig.getString("verbose", "{prefix} &fИгрок &x&F&B&0&8&0&8{player} &fпровалил &7&x&F&B&0&8&0&8{check_name} &7[&fx&x&F&B&0&8&0&8{vl}&7] &7{verbose}").replace("{prefix}", prefix);

        historyHeaderMessage = messagesConfig.getString("history-header", "{prefix} &fИстория проверок игрока &c{player} &7({page}&8/&7{max_pages})").replace("{prefix}", prefix);
        historyEntryMessage = messagesConfig.getString("history-entry", "{prefix} &8[&b{server}&8] &fПровалил &b{check_name} &f(x&c{vl}&f) &7{verbose} (&b{time_ago} назад&7)").replace("{prefix}", prefix);
        historyOnlyPlayerMessage = messagesConfig.getString("history-only-player", "{prefix} &#F3F3F3Меню истории может открыть только игрок.").replace("{prefix}", prefix);
        historyNoDataMessage = messagesConfig.getString("history-no-data", "{prefix} &#F3F3F3Нет данных истории для игрока &#F65943{player}&#F3F3F3.").replace("{prefix}", prefix);

        reloadingMessage = messagesConfig.getString("reloading", "{prefix} &fПерезагрузка конфигурации...").replace("{prefix}", prefix);
        reloadedMessage = messagesConfig.getString("reloaded", "{prefix} &fКонфигурация успешно перезагружена...").replace("{prefix}", prefix);

        monitorActionBarFormat = messagesConfig.getString("monitor.action_bar.format", "&b&lAI &f{player} &8• &7Prob {probability_color}{probability}% &8{trend} &8• &7Buffer &e{buffer} &8• &7Ping {ping_color}{ping}ms").replace("{prefix}", prefix);
        monitorWaitingFormat = messagesConfig.getString("monitor.action_bar.waiting", "&b&lAI &f{player} &8• &7ожидание результата анализа...").replace("{prefix}", prefix);
        monitorTrendUpFormat = messagesConfig.getString("monitor.action_bar.trend.up", "&c▲ +{value}").replace("{prefix}", prefix);
        monitorTrendDownFormat = messagesConfig.getString("monitor.action_bar.trend.down", "&a▼ {value}").replace("{prefix}", prefix);
        monitorTrendEqualFormat = messagesConfig.getString("monitor.action_bar.trend.equal", "&8=").replace("{prefix}", prefix);

        monitorOnlyPlayerMessage = messagesConfig.getString("monitor.only_player", "{prefix} &fЭту команду может выполнить только игрок.").replace("{prefix}", prefix);
        monitorEnabledMessage = messagesConfig.getString("monitor.enabled", "{prefix} &fМонитор включён для &7{player}&f.").replace("{prefix}", prefix);
        monitorDisabledMessage = messagesConfig.getString("monitor.disabled", "{prefix} &fМонитор выключен.").replace("{prefix}", prefix);
        monitorNotRunningMessage = messagesConfig.getString("monitor.not_running", "{prefix} &fМонитор сейчас не запущен.").replace("{prefix}", prefix);
        monitorSwitchedMessage = messagesConfig.getString("monitor.switched", "{prefix} &fЦель монитора изменена на &7{player}&f.").replace("{prefix}", prefix);
        monitorPlayerNotFoundMessage = messagesConfig.getString("monitor.player_not_found", "{prefix} &fИгрок не найден.").replace("{prefix}", prefix);
        monitorTargetLeftMessage = messagesConfig.getString("monitor.target_left", "{prefix} &fМонитор остановлен: игрок &7{player} &fвышел с сервера.").replace("{prefix}", prefix);

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
        return "%s%.2f".formatted(color, percent * 100);
    }
}