package ru.raveon;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ru.raveon.api.itemstack.ItemStackServices;
import ru.raveon.api.models.analyze.AnalyzeService;
import ru.raveon.api.redis.RedisManager;
import ru.raveon.command.CommandManager;
import ru.raveon.config.*;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.config.anticheat.HologramConfigManager;
import ru.raveon.config.anticheat.PunishmentConfigManager;
import ru.raveon.config.datacollect.DataCollectConfigManager;
import ru.raveon.integration.customplaceholder.PlaceholderIntegration;
import ru.raveon.integration.customplaceholder.impl.PluginPlaceholder;
import ru.raveon.listeners.bukkit.MenuListener;
import ru.raveon.manager.*;
import ru.raveon.manager.alert.AlertManager;
import ru.raveon.manager.alert.ViolationManager;
import ru.raveon.manager.analytic.MonitorManager;
import ru.raveon.manager.anticheat.PlayerDataManager;
import ru.raveon.manager.analytic.hologram.HologramManager;
import ru.raveon.service.PlayerOnlineService;
import ru.raveon.service.analyze.AnalyzeBatchDispatcher;
import ru.raveon.service.analyze.FlatBufferAnalyzeService;
import ru.raveon.utils.VersionHelper;
import ru.raveon.utils.entity.TargetEntityIndex;
import ru.raveon.utils.entity.TargetEntityIndexListener;

import java.lang.reflect.Constructor;

@Getter
public class Raveon extends JavaPlugin {

    public static Raveon INSTANCE;

    private PlayerDataManager playerDataManager;
    private PlaceholderIntegration placeholderIntegration;

    private MainConfigManager mainConfigManager;
    private ChecksConfigManager checksConfigManager;
    private DataCollectConfigManager dataCollectConfigManager;
    private HologramConfigManager hologramConfigManager;
    private PunishmentConfigManager punishmentConfigManager;

    private AIResultManager aiResultManager;
    private AnalyzeService analyzeService;

    private ViolationManager violationManager;
    private AlertManager alertManager;

    private HologramManager hologramManager;
    private MonitorManager monitorManager;

    private AnalyzeBatchDispatcher analyzeBatchDispatcher;
    private CommandManager commandManager;

    private TargetEntityIndex targetEntityIndex;
    private PacketManager packetManager;

    private RedisManager redisManager;
    private PlayerOnlineService playerOnlineService;

    @Override
    public void onEnable() {
        INSTANCE = this;

        this.packetManager = new PacketManager();
        packetManager.register();

        this.playerDataManager = new PlayerDataManager();

        this.placeholderIntegration = new PluginPlaceholder();
        placeholderIntegration.init(this);

        this.targetEntityIndex = new TargetEntityIndex();

        this.mainConfigManager = new MainConfigManager(this);
        this.checksConfigManager = new ChecksConfigManager(this);
        this.dataCollectConfigManager = new DataCollectConfigManager(this);
        this.hologramConfigManager = new HologramConfigManager(this);
        this.punishmentConfigManager = new PunishmentConfigManager(this);
        initRedis();

        this.aiResultManager = new AIResultManager();
        this.analyzeBatchDispatcher = new AnalyzeBatchDispatcher(this, checksConfigManager);
        this.analyzeBatchDispatcher.start();
        this.analyzeService = new FlatBufferAnalyzeService(checksConfigManager, analyzeBatchDispatcher);
        this.alertManager = new AlertManager(mainConfigManager);
        this.violationManager = new ViolationManager();

        getServer().getPluginManager().registerEvents(new TargetEntityIndexListener(targetEntityIndex), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        targetEntityIndex.initialize();

        this.commandManager = new CommandManager(this);
        this.commandManager.registerCommands();
        this.commandManager.registerWrappers();

        this.hologramManager = new HologramManager();
        this.hologramManager.start();

        this.monitorManager = new MonitorManager(this,mainConfigManager);

        this.playerDataManager.loadOnlinePlayers();

        ItemStackServices.setSkullHead(createSkullHead());

        initRedis();
    }

    private void initRedis() {
        redisManager = new RedisManager(mainConfigManager.getRedisConfig(), null);
        playerOnlineService = new PlayerOnlineService(redisManager, 60000L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                playerOnlineService.heartbeat(
                        player.getUniqueId(),
                        player.getName()
                );
            }

            playerOnlineService.cleanup();
        }, 20L * 10L, 20L * 10L);
    }

    @Override
    public void onDisable() {
        if (packetManager != null) {
            packetManager.unregister();
        }
        if (analyzeBatchDispatcher != null) {
            analyzeBatchDispatcher.stop();
        }
        if (violationManager != null) {
            violationManager.shutdown();
        }
        if (hologramManager != null) {
            hologramManager.stop();
        }
        if (monitorManager != null) {
            monitorManager.shutdown();
        }
    }


    public void registerCommand(String commandName, CommandExecutor executor) {
        try {
            CommandMap commandMap = getServer().getCommandMap();
            Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand command = constructor.newInstance(commandName, this);

            command.setExecutor(executor);
            commandMap.register(getDescription().getName(), command);
        } catch (Exception exception) {
            getLogger().severe("Unable to register command: " + commandName + ". Error: " + exception.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private ItemStack createSkullHead() {
        if (VersionHelper.IS_ITEM_LEGACY) {
            return new ItemStack(Material.valueOf("SKULL_ITEM"), 1, (short) 3);
        }
        return new ItemStack(Material.PLAYER_HEAD, 1);
    }

    public static String serverId() {
        return INSTANCE.placeholderIntegration.getPlaceholder("server_id", "unknown");
    }

    public static String serverName() {
        return INSTANCE.placeholderIntegration.getPlaceholder("server_name", "unknown");
    }
}
