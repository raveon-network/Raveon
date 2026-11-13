package ru.raveon.config.anticheat;

import lombok.Getter;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.model.ModelId;
import ru.raveon.utils.StringColorize;
import java.util.List;

@Getter
public class HologramConfigManager extends ConfigManager {
    private static final String DEFAULT_LINE = "&7[{model}] {prob_color}&l{prob}% &7| {avg_color}&l{avg}% &7| &f{buffer}";
    private boolean enabled;
    private double lineSpacing;
    private double offset;
    private boolean belowNameEnabled;
    private boolean alsoShowHologramWithBelowName;
    private List<String> lines;
    private String belowNameLine;
    private int modelRotationIntervalTicks;

    public HologramConfigManager(Plugin plugin) {
        super(plugin);
        initialize();
    }

    @Override
    public void loadConfigs() {
        CustomConfig config = new CustomConfig("features/hologram.yml", plugin);
        if (config.getConfig().contains("model_rotation") && !config.getConfig().isConfigurationSection("model_rotation"))
            throw new IllegalArgumentException("model_rotation must be a section");
        boolean changed = false;
        if (!config.getConfig().contains("lines")) {
            config.getConfig().set("lines", List.of(config.getRawString("floating_line", DEFAULT_LINE)));
            changed = true;
        }
        Object raw = config.getConfig().get("lines");
        if (raw instanceof List<?> entries && entries.stream().allMatch(String.class::isInstance)) {
            List<String> literal = entries.stream().map(String.class::cast)
                    .map(line -> line.replaceAll("\\{lang:[^}]+}", java.util.regex.Matcher.quoteReplacement(DEFAULT_LINE))).toList();
            if (!literal.equals(entries)) {
                config.getConfig().set("lines", literal);
                changed = true;
            }
        }
        for (String obsolete : List.of("floating_line", "floating_lines", "model_rotation.names")) {
            if (config.getConfig().contains(obsolete)) {
                config.getConfig().set(obsolete, null);
                changed = true;
            }
        }
        if (!config.getConfig().contains("model_rotation.interval_ticks")) {
            config.getConfig().set("model_rotation.interval_ticks", 80);
            changed = true;
        }
        if (changed) config.save();
        addCustomConfig(config);
    }

    @Override
    public void loadValues() {
        CustomConfig config = getCustomConfig("features/hologram.yml");
        enabled = config.getBoolean("enable", false);
        lineSpacing = config.requireDouble("line_spacing", 0.28, 0.01, 16);
        offset = config.requireDouble("offset", 2.5, -64, 64);
        belowNameEnabled = config.getBoolean("below_name.enable", true);
        alsoShowHologramWithBelowName = config.getBoolean("below_name.also_show_hologram", false);
        modelRotationIntervalTicks = config.requireInt("model_rotation.interval_ticks", 80, 1, 72000);
        Object configured = config.getConfig().get("lines", List.of(DEFAULT_LINE));
        if (!(configured instanceof List<?> values) || values.stream().anyMatch(line -> !(line instanceof String)))
            throw new IllegalArgumentException("Hologram lines must be a list of text");
        lines = values.stream().map(String.class::cast).map(StringColorize::parse).toList();
        if (lines.size() > 32 || lines.stream().anyMatch(line -> line.length() > 4096))
            throw new IllegalArgumentException("Hologram lines exceed the limit");
        belowNameLine = lines.size() == 1 ? lines.get(0) : "";
    }

    public String getModelName(ModelId model) {
        return ru.raveon.Raveon.INSTANCE.getChecksConfigManager().getModelShortTitle(model);
    }
}
