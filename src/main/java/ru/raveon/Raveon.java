package ru.raveon;

import lombok.Getter;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import ru.raveon.api.models.analyze.AnalyzeService;
import ru.raveon.api.redis.RedisManager;
import ru.raveon.command.CommandManager;
import ru.raveon.config.*;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.config.anticheat.HologramConfigManager;
import ru.raveon.config.datacollect.DataCollectConfigManager;
import ru.raveon.integration.customplaceholder.PlaceholderIntegration;
import ru.raveon.integration.customplaceholder.impl.PluginPlaceholder;
import ru.raveon.listeners.bukkit.MenuListener;
import ru.raveon.manager.*;
import ru.raveon.manager.alert.AlertManager;
import ru.raveon.manager.alert.ViolationManager;
import ru.raveon.manager.analytic.MonitorManager;
import ru.raveon.manager.analytic.hologram.HologramManager;
import ru.raveon.manager.anticheat.PlayerDataManager;
import ru.raveon.manager.anticheat.PunishmentManager;
import ru.raveon.database.modules.PunishmentDatabase;
import ru.raveon.listeners.bukkit.MitigationListener;
import ru.raveon.service.PlayerOnlineService;
import ru.raveon.service.analyze.AnalyzeBatchDispatcher;
import ru.raveon.service.analyze.AnalyzeConnection;
import ru.raveon.service.analyze.FlatBufferAnalyzeService;
import ru.raveon.utils.SchedulerUtils;
import ru.raveon.utils.entity.TargetEntityIndex;
import ru.raveon.utils.entity.TargetEntityIndexListener;

import java.lang.reflect.Constructor;

@Getter
public class Raveon extends JavaPlugin {

    public static Raveon INSTANCE;

    private PlayerDataManager playerDataManager;
    private PlaceholderIntegration placeholderIntegration;

    private volatile RuntimeConfiguration configuration;

    public ru.raveon.service.LanguageService getLanguageService() { return getMainConfigManager().getLanguageService(); }

    public MainConfigManager getMainConfigManager() { return configuration == null ? null : configuration.main(); }
    public ChecksConfigManager getChecksConfigManager() { return configuration.checks(); }
    public DataCollectConfigManager getDataCollectConfigManager() { return configuration.datasets(); }
    public HologramConfigManager getHologramConfigManager() { return configuration.holograms(); }

    public synchronized void reloadConfiguration() {
        RuntimeConfiguration candidate = RuntimeConfiguration.load(this, configuration);
        configuration = candidate;
        for (var player : playerDataManager.getEntries()) {
            Player entity = player.getBukkitPlayer();
            if (entity != null) SchedulerUtils.runEntity(this, entity, player::reload);
        }
        hologramManager.handleConfigReload();
    }

    private AIResultManager aiResultManager;
    private AnalyzeService analyzeService;

    private PunishmentManager punishmentManager;
    private ViolationManager violationManager;
    private AlertManager alertManager;

    private HologramManager hologramManager;
    private MonitorManager monitorManager;

    private AnalyzeBatchDispatcher analyzeBatchDispatcher;
    private AnalyzeConnection analyzeConnection;
    private CommandManager commandManager;

    private TargetEntityIndex targetEntityIndex;
    private PacketManager packetManager;

    private RedisManager redisManager;
    private PlayerOnlineService playerOnlineService;
    private SchedulerUtils.TaskHandle heartbeatTask;

    @Override
    public void onEnable() {
        INSTANCE = this;

        this.packetManager = new PacketManager();

        this.playerDataManager = new PlayerDataManager();

        this.placeholderIntegration = new PluginPlaceholder();
        placeholderIntegration.init(this);

        this.targetEntityIndex = new TargetEntityIndex();

        this.configuration = RuntimeConfiguration.load(this, null);
        ru.raveon.service.train.UploadService.start();
        initRedis();
        this.aiResultManager = new AIResultManager();
        this.analyzeBatchDispatcher = new AnalyzeBatchDispatcher(this, this::getChecksConfigManager);
        this.analyzeBatchDispatcher.start();
        this.analyzeConnection =
                new AnalyzeConnection(this, this::getChecksConfigManager, analyzeBatchDispatcher);
        this.analyzeConnection.refresh();
        this.analyzeService =
                new FlatBufferAnalyzeService(this::getChecksConfigManager, analyzeBatchDispatcher);
        this.alertManager = new AlertManager(this::getMainConfigManager);
        this.violationManager = new ViolationManager();
        PunishmentDatabase punishmentDatabase =
                new PunishmentDatabase(getMainConfigManager().getDatabaseManager(), serverId());
        punishmentDatabase.createTable();
        this.punishmentManager = new PunishmentManager(this, punishmentDatabase);

        getServer()
                .getPluginManager()
                .registerEvents(new TargetEntityIndexListener(targetEntityIndex), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new ru.raveon.listeners.bukkit.TelemetryListener(), this);
        getServer().getPluginManager().registerEvents(new MitigationListener(this), this);
        getServer().getPluginManager().registerEvents(packetManager.getHologramListener(), this);
        targetEntityIndex.initialize();

        this.commandManager = new CommandManager(this);
        this.commandManager.registerCommands();
        this.commandManager.registerWrappers();

        this.hologramManager = new HologramManager();
        this.hologramManager.start();

        this.monitorManager = new MonitorManager(this);

        getServer().getPluginManager().registerEvents(monitorManager, this);
        packetManager.register();
        this.playerDataManager.loadOnlinePlayers();

    }

    private void initRedis() {
        redisManager = new RedisManager(getMainConfigManager().getRedisConfig());
        playerOnlineService = new PlayerOnlineService(redisManager, 60000L);
        heartbeatTask =
                SchedulerUtils.runTimerAsync(
                        this,
                        () -> {
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                playerOnlineService.heartbeat(
                                        player.getUniqueId(), player.getName());
                            }

                            playerOnlineService.cleanup();
                        },
                        20L * 10L,
                        20L * 10L);
    }

    @Override
    public void onDisable() {
        if (packetManager != null) cleanup("packet listeners", packetManager::unregister);
        if (heartbeatTask != null) cleanup("heartbeat", heartbeatTask::cancel);
        if (playerDataManager != null) {
            for (var player : playerDataManager.getEntries()) {
                cleanup("player processing", player.getCheckManager()::close);
                cleanup("Ultra state", player.getUltraData()::reset);

            }
        }
        if (analyzeBatchDispatcher != null) cleanup("HTTP", analyzeBatchDispatcher::stop);
        if (playerDataManager != null) cleanup("dataset sessions", () -> {
            try {
                playerDataManager.stopAllDatasets().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(error);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        if (hologramManager != null) cleanup("holograms", hologramManager::stop);
        if (monitorManager != null) cleanup("monitor", monitorManager::shutdown);
        cleanup("dataset writer", ru.raveon.service.train.UploadService::shutdown);
        if (violationManager != null) cleanup("database", violationManager::shutdown);
        else if (configuration != null) cleanup("database", () -> getMainConfigManager().getDatabaseManager().shutdown().join());
        if (redisManager != null) cleanup("Redis", redisManager::onDisable);
        if (targetEntityIndex != null) cleanup("entity index", targetEntityIndex::clear);
    }

    private void cleanup(String service, Runnable close) {
        try {
            close.run();
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to close " + service, error);
        }
    }

    public void registerCommand(String commandName, CommandExecutor executor) {
        try {
            CommandMap commandMap = getServer().getCommandMap();
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand command = constructor.newInstance(commandName, this);

            command.setExecutor(executor);
            commandMap.register(getDescription().getName(), command);
        } catch (Exception exception) {
            getLogger()
                    .severe(
                            "Unable to register command: "
                                    + commandName
                                    + ". Error: "
                                    + exception.getMessage());
        }
    }

    public static String serverId() {
        return INSTANCE.placeholderIntegration.getPlaceholder("server_id", "unknown");
    }

    public static String serverName() {
        return INSTANCE.placeholderIntegration.getPlaceholder("server_name", "unknown");
    }
}
