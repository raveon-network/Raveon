package ru.raveon.config.anticheat;

import lombok.Getter;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.model.ModelId;
import ru.raveon.service.LanguageService;
import ru.raveon.utils.StringColorize;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Getter
public class HologramConfigManager extends ConfigManager {
    private boolean enabled;
    private double lineSpacing;
    private double offset;
    private boolean belowNameEnabled;
    private boolean alsoShowHologramWithBelowName;
    private List<String> lines;
    private String belowNameLine;
    private int modelRotationIntervalTicks;
    private Map<ModelId, String> modelNames;
    private Map<ModelId, String> floatingLines;
    private final LanguageService languageService;

    public HologramConfigManager(Plugin plugin, LanguageService languageService) {
        super(plugin);
        this.languageService = languageService;
        initialize();
    }

    @Override
    public void loadConfigs() {
        CustomConfig config = new CustomConfig("features/hologram.yml", plugin);
        for (String path : List.of("model_rotation", "model_rotation.names"))
            if (config.getConfig().contains(path) && !config.getConfig().isConfigurationSection(path))
                throw new IllegalArgumentException(path + " must be a section");
        boolean changed = false;
        if (!config.getConfig().contains("model_rotation.interval_ticks")) {
            config.getConfig().set("model_rotation.interval_ticks", 80);
            changed = true;
        }
        for (ModelId model : ModelId.values()) {
            String path = "model_rotation.names." + model.key();
            if (!config.getConfig().contains(path)) {
                config.getConfig().set(path, model.displayName());
                changed = true;
            }
        }
        if (changed) config.save();
        addCustomConfig(config);
    }

    @Override
    public void loadValues() {
        CustomConfig config = getCustomConfig("features/hologram.yml");
        var content = languageService.localize(config.getConfig());
        enabled = config.getBoolean("enable", false);
        lineSpacing = config.requireDouble("line_spacing", 0.28, 0.01, 16);
        offset = config.requireDouble("offset", 2.5, -64, 64);
        belowNameEnabled = content.getBoolean("below_name.enable", true);
        alsoShowHologramWithBelowName = content.getBoolean("below_name.also_show_hologram", false);
        modelRotationIntervalTicks = config.requireInt("model_rotation.interval_ticks", 80, 1, 72000);
        lines = content.getStringList("lines").stream().map(StringColorize::parse).toList();
        if (lines.size() > 32 || lines.stream().anyMatch(line -> line.length() > 4096))
            throw new IllegalArgumentException("Hologram lines exceed the limit");
        belowNameLine = lines.isEmpty() ? "" : lines.get(0);
        String defaultLine = content.getString("floating_line", languageService.text("holograms.line"));
        Map<ModelId, String> names = new EnumMap<>(ModelId.class);
        Map<ModelId, String> templates = new EnumMap<>(ModelId.class);
        for (ModelId model : ModelId.values()) {
            String name = config.getRawString("model_rotation.names." + model.key(), model.displayName());
            if (name.isBlank() || name.length() > 128)
                throw new IllegalArgumentException("Invalid hologram model name: " + model.key());
            names.put(model, StringColorize.parse(name));
            String template = content.getString("floating_lines." + model.key(), defaultLine);
            if (template == null || template.length() > 4096)
                throw new IllegalArgumentException("Floating hologram line exceeds the limit: " + model.key());
            templates.put(model, StringColorize.parse(template));
        }
        modelNames = Map.copyOf(names);
        floatingLines = Map.copyOf(templates);
    }

    public String getModelName(ModelId model) {
        return modelNames.get(model);
    }

    public String getFloatingLine(ModelId model) {
        return floatingLines.get(model);
    }

    public String getProLine() {
        return getFloatingLine(ModelId.PRO);
    }

    public String getUltraLine() {
        return getFloatingLine(ModelId.ULTRA);
    }
}
