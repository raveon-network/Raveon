package ru.raveon.config.anticheat;

import lombok.Getter;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.utils.StringColorize;

import java.util.List;

@Getter
public class HologramConfigManager extends ConfigManager {
    private boolean enabled;
    private double lineSpacing;

    private int historyLines;
    private double offset;
    private int historyProbsPerLine;

    private List<String> lines;

    public HologramConfigManager(Plugin plugin) {
        super(plugin);
    }

    @Override
    public void loadConfigs() {
        addCustomConfig(new CustomConfig("features/hologram.yml", plugin));
    }

    @Override
    public void loadValues() {
        CustomConfig hologramConfig = getCustomConfig("features/hologram.yml");

        enabled = hologramConfig.getBoolean("enable", false);
        lineSpacing = hologramConfig.getConfig().getDouble("line_spacing", 0.28);
        offset = hologramConfig.getConfig().getDouble("offset", 3.4);

        historyLines = Math.max(1, hologramConfig.getInt("history_lines", 2));
        historyProbsPerLine = Math.max(1, hologramConfig.getInt("history_probs_per_line", 5));

        lines = hologramConfig.getConfig().getStringList("lines").stream()
                .map(StringColorize::parse)
                .toList();
    }
}