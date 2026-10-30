package ru.raveon.config.datacollect;

import lombok.Getter;

import org.bukkit.plugin.Plugin;

import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.api.models.TrainStatisticInfoType;
import ru.raveon.model.ModelId;

@Getter
public class DataCollectConfigManager extends ConfigManager {
    private int framesToCollect;
    private int ultraFramesToCollect;
    private long ultraCombatTimer;
    private long combatTimer;
    private boolean clearBufferOnCombatTimeout;

    private boolean autosaveStatus;
    private int autosaveValue;

    private TrainStatisticInfoType infoType;
    public int getFramesToCollect(ModelId model) {
        return model == ModelId.PRO ? framesToCollect : ultraFramesToCollect;
    }

    public long getCombatTimer(ModelId model) {
        return model == ModelId.PRO ? combatTimer : ultraCombatTimer;
    }

    public DataCollectConfigManager(Plugin plugin) {
        super(plugin);
        initialize();
    }

    @Override
    public void loadConfigs() {
        addCustomConfig(new CustomConfig("features/datacollect.yml", plugin));
    }

    @Override
    public void loadValues() {
        CustomConfig config = getCustomConfig("features/datacollect.yml");
        framesToCollect = config.requireInt("frames", 40, 1, 8192);
        ultraFramesToCollect = config.requireInt("ultra.frames", 64, 1, 8192);
        ultraCombatTimer = config.requireInt("ultra.combat_timer", 64, 0, 72000) * 50L;
        combatTimer = config.requireInt("combat_timer", 20, 0, 72000) * 50L;
        clearBufferOnCombatTimeout = config.getBoolean("clear_buffer_on_combat_timeout", false);
        autosaveStatus = config.getBoolean("auto_save.enable", true);
        autosaveValue = config.requireInt("auto_save.value", 40, 1, 1000000);

        String infoTypeStr = config.getString("info_statistic.type", "BOSSBAR");
        if (!"BOSSBAR".equalsIgnoreCase(infoTypeStr) && !"ACTIONBAR".equalsIgnoreCase(infoTypeStr))
            throw new IllegalArgumentException("Unknown info_statistic.type");
        infoType = TrainStatisticInfoType.getByName(infoTypeStr);
    }
}
