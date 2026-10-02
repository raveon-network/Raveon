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
    private double offset;

    private boolean belowNameEnabled;
    private boolean alsoShowHologramWithBelowName;

    private List<String> lines;

    private String belowNameLine;

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
        offset = hologramConfig.getConfig().getDouble("offset", 2.5);

        belowNameEnabled = hologramConfig.getConfig().getBoolean("below_name.enable", true);
        alsoShowHologramWithBelowName = hologramConfig.getConfig()
                .getBoolean("below_name.also_show_hologram", false);

        lines = hologramConfig.getConfig().getStringList("lines").stream()
                .map(StringColorize::parse)
                .toList();

        belowNameLine = lines.isEmpty() ? "" : lines.get(0);
    }
}