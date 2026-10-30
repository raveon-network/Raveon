package ru.raveon.config.anticheat;

import lombok.Getter;

import org.bukkit.plugin.Plugin;

import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.service.LanguageService;
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
    private String proLine;
    private String ultraLine;

    private final LanguageService languageService;

    public HologramConfigManager(Plugin plugin, LanguageService languageService) {
        super(plugin);
        this.languageService = languageService;
        initialize();
    }

    @Override
    public void loadConfigs() {
        addCustomConfig(new CustomConfig("features/hologram.yml", plugin));
    }

    @Override
    public void loadValues() {
        CustomConfig hologramConfig = getCustomConfig("features/hologram.yml");

        var content = languageService.localize(hologramConfig.getConfig());

        enabled = hologramConfig.getBoolean("enable", false);
        lineSpacing = hologramConfig.requireDouble("line_spacing", 0.28, 0.01, 16);
        offset = hologramConfig.requireDouble("offset", 2.5, -64, 64);

        belowNameEnabled = content.getBoolean("below_name.enable", true);
        alsoShowHologramWithBelowName =
                content.getBoolean("below_name.also_show_hologram", false);

        lines =
                content.getStringList("lines").stream()
                        .map(StringColorize::parse)
                        .toList();

        if (lines.size() > 32 || lines.stream().anyMatch(line -> line.length() > 4096))
            throw new IllegalArgumentException("Hologram lines exceed the limit");
        belowNameLine = lines.isEmpty() ? "" : lines.get(0);
        // Existing configs keep their formatting; below-name still uses the legacy first line.
        String values =
                belowNameLine.isEmpty()
                        ? "{prob_color}&l{prob}% &7| {avg_color}&l{avg}% &7| &f{buffer}"
                        : belowNameLine;
        proLine =
                StringColorize.parse(
                        content.getString("floating_lines.pro", "&7[Pro] " + values));
        ultraLine =
                StringColorize.parse(
                        content.getString("floating_lines.ultra", "&7[Ultra] " + values));
        if (proLine == null || ultraLine == null || proLine.length() > 4096 || ultraLine.length() > 4096)
            throw new IllegalArgumentException("Floating hologram lines exceed the limit");
    }
}
