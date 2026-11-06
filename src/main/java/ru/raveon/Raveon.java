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

    public java.util.concurrent.CompletableFuture<Void> reloadConfigurationAsync() {
        RuntimeConfiguration previous = configuration;
        ChecksConfigManager requested;
        try {
            requested = new ChecksConfigManager(this);
            if (!previous.checks().getAnalyzeServer().equals(requested.getAnalyzeServer()))
                throw new IllegalArgumentException("Changing analyze.server requires a restart");
        } catch (RuntimeException error) {
            return java.util.concurrent.CompletableFuture.failedFuture(error);
        }
        try {
            return analyzeConnection.fetch(requested)
                    .thenCompose(result -> analyzeConnection.prepare(previous, requested, result))
                    .thenCompose(prepared -> applyPreparedProfile(previous, requested, prepared, true));
        } catch (RuntimeException error) {
            return java.util.concurrent.CompletableFuture.failedFuture(error);
        }
    }

    public java.util.concurrent.CompletableFuture<Void> applyPreparedProfile(RuntimeConfiguration previous,
            ChecksConfigManager requested, AnalyzeConnection.Prepared prepared, boolean explicit) {
        var applied = new java.util.concurrent.CompletableFuture<Void>();
        try {
            SchedulerUtils.run(this, () -> {
                try {
                    if (!isEnabled() || configuration != previous)
                        throw new IllegalStateException("Configuration changed during profile fetch; retry reload");
                    var result = prepared.result();
                    if (!explicit && result.profile().equals(previous.checks().getRemoteProfile())
                            && !analyzeConnection.localChanged(prepared)) { applied.complete(null); return; }
                    RuntimeConfiguration candidate = RuntimeConfiguration.load(this, previous, result.profile());
                    if (!candidate.checks().getApiKey().equals(requested.getApiKey())
                            || !candidate.checks().getAnalyzeServer().equals(requested.getAnalyzeServer()))
                        throw new IllegalStateException("Connection settings changed during profile fetch; retry reload");
                    analyzeConnection.commitRules(prepared, previous);
                    configuration = candidate;
                    analyzeConnection.acknowledge(prepared);
                    analyzeBatchDispatcher.connected(result.profile().models().keySet());
                    for (var player : playerDataManager.getEntries()) {
                        Player entity = player.getBukkitPlayer();
                        if (entity != null) SchedulerUtils.runEntity(this, entity, player::reload);
                    }
                    hologramManager.handleConfigReload();
                    monitorManager.handleConfigReload();
                    if (requested.getApiKey().isEmpty())
                        getLogger().warning(getLanguageService().plain("logs.plugin-not-connected"));
                    else getLogger().info("Applied model profile " + result.profile().crc() + ": " + result.modelNames());
                    applied.complete(null);
                } catch (RuntimeException failure) { applied.completeExceptionally(failure); }
            });
        } catch (RuntimeException error) { applied.completeExceptionally(error); }
        return applied;
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
                new AnalyzeConnection(this, this::getChecksConfigManager);
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
        var viewerPreferences = new ru.raveon.listeners.bukkit.ViewerPreferencesListener(this);
        getServer().getPluginManager().registerEvents(viewerPreferences, this);
        packetManager.register();
        this.playerDataManager.loadOnlinePlayers();
        for (Player player : Bukkit.getOnlinePlayers()) viewerPreferences.restore(player);
        this.analyzeConnection.refresh();
        this.analyzeConnection.startWatching();

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
        if (analyzeConnection != null) cleanup("profile watcher", analyzeConnection::stop);
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
        if (playerDataManager != null) cleanup("session buffer cache", playerDataManager.getBufferCache()::clear);
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
