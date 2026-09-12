package ru.raveon.config.datacollect;

import lombok.Getter;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.api.models.TrainStatisticInfoType;

@Getter
public class DataCollectConfigManager extends ConfigManager {
    private int framesToCollect;
    private long combatTimer;
    private boolean clearBufferOnCombatTimeout;

    private boolean autosaveStatus;
    private int autosaveValue;

    private TrainStatisticInfoType infoType;
    private String infoMessage;

    private String messageStartHelp;
    private String messageStartNoneSelected;
    private String messageStopHelp;
    private String messageStopNoneCollecting;
    private String messageAllOffline;
    private String messageAllAlreadyCollecting;
    private String messageAllNoneType;
    private String messageAllStart;
    private String messageAllStop;
    private String messageAllStopAuto;

    public DataCollectConfigManager(Plugin plugin) {
        super(plugin);
    }

    @Override
    public void loadConfigs() {
        addCustomConfig(new CustomConfig("features/datacollect.yml", plugin));
    }

    @Override
    public void loadValues() {
        CustomConfig config = getCustomConfig("features/datacollect.yml");
        framesToCollect = config.getInt("frames", 40);
        combatTimer = config.getInt("combat_timer", 20) * 50L;
        clearBufferOnCombatTimeout = config.getBoolean("clear_buffer_on_combat_timeout", false);
        autosaveStatus = config.getBoolean("auto_save.enable", true);
        autosaveValue = config.getInt("auto_save.value", 40);

        String infoTypeStr = config.getString("info_statistic.type", "BOSSBAR");
        infoType = TrainStatisticInfoType.getByName(infoTypeStr);
        infoMessage = config.getString(
                "info_statistic.line",
                "&#D3B4FC[DataCollect] &7{player}&f collected: &7{frames_collecting}&f (buffer: &7{frames_size}&f) uploaded: &7{sending}"
        );

        messageStartHelp = config.getString(
                "messages.start.help",
                "&#5699DE[DataCollect] &fUsage: &7/raveon datacollect start <collector> <cheat|legit> [cheat_name]"
        );
        messageStartNoneSelected = config.getString(
                "messages.start.none_selected",
                "&#5699DE[DataCollect] &fFor cheat data collection you must specify cheat name"
        );

        messageStopHelp = config.getString(
                "messages.stop.help",
                "&#5699DE[DataCollect] &fUsage: &7/raveon datacollect stop <collector>"
        );
        messageStopNoneCollecting = config.getString(
                "messages.stop.no_collecting",
                "&#5699DE[DataCollect] &fPlayer {target} is not collecting data"
        );

        messageAllOffline = config.getString(
                "messages.all.offline",
                "&#5699DE[DataCollect] &fPlayer &7{target} &fis offline"
        );
        messageAllAlreadyCollecting = config.getString(
                "messages.all.already_collecting",
                "&#5699DE[DataCollect] &fData is already being collected from &7{target}"
        );
        messageAllNoneType = config.getString(
                "messages.all.none_type",
                "&#5699DE[DataCollect] &fInvalid collection type. Available: &7[cheat/legit]"
        );
        messageAllStart = config.getString(
                "messages.start.start",
                "&#5699DE[DataCollect] &fStarted dataset collection for &7{target}"
        );
        messageAllStop = config.getString(
                "messages.stop.stop",
                "&#5699DE[DataCollect] &fStopped dataset collection for &7{target}"
        );
        messageAllStopAuto = config.getString(
                "messages.stop.auto_stop",
                "&#5699DE[DataCollect] &fDataset collection stopped for &7{target}"
        );
    }
}
