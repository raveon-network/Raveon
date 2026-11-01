package ru.raveon.config.anticheat;

import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.model.ModelId;
import ru.raveon.service.LanguageService;
import ru.raveon.utils.StringColorize;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
public class HologramConfigManager extends ConfigManager {
    private static final String DEFAULT_LINE =
            "&7[{model}] {prob_color}&l{prob}% &7| {avg_color}&l{avg}% &7| &f{buffer}";
    private static final Pattern LEGACY_REFERENCE = Pattern.compile("\\{lang:(holograms\\.[a-zA-Z0-9_.-]+)}");
    private boolean enabled;
    private double lineSpacing;
    private double offset;
    private boolean belowNameEnabled;
    private boolean alsoShowHologramWithBelowName;
    private List<String> lines;
    private String belowNameLine;
    private int modelRotationIntervalTicks;
    private Map<ModelId, String> modelNames;
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
        boolean migrate = hasLanguageReferences(config) || config.getConfig().contains("floating_line")
                || config.getConfig().contains("floating_lines");
        boolean changed = inlineLegacyTranslations(config);
        if (!config.getConfig().contains("lines")) {
            config.getConfig().set("lines", List.of(config.getRawString("floating_line", DEFAULT_LINE)));
            changed = true;
        }
        for (String obsolete : List.of("floating_line", "floating_lines")) {
            if (config.getConfig().contains(obsolete)) {
                config.getConfig().set(obsolete, null);
                changed = true;
            }
        }
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
        if (changed) {
            if (migrate) {
                var original = config.getFile().toPath();
                var backup = original.resolveSibling("hologram.yml.bak");
                try {
                    if (!Files.exists(backup)) Files.copy(original, backup);
                } catch (IOException error) {
                    throw new IllegalArgumentException("Cannot back up the old hologram configuration", error);
                }
            }
            config.save();
        }
        addCustomConfig(config);
    }

    private static boolean hasLanguageReferences(CustomConfig config) {
        return config.getConfig().getValues(true).values().stream()
                .anyMatch(value -> value != null && !(value instanceof ConfigurationSection)
                        && value.toString().contains("{lang:"));
    }

    private boolean inlineLegacyTranslations(CustomConfig config) {
        if (!hasLanguageReferences(config)) return false;
        YamlConfiguration legacy = new YamlConfiguration();
        var file = plugin.getDataFolder().toPath().resolve("lang")
                .resolve(languageService.language()).resolve("hologram.yml");
        if (!Files.exists(file))
            file = plugin.getDataFolder().toPath().resolve("lang").resolve(languageService.language() + ".yml");
        try {
            if (Files.exists(file))
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    legacy.load(reader);
                }
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("Cannot migrate hologram translations", error);
        }
        YamlConfiguration prepared = new YamlConfiguration();
        for (String key : config.getConfig().getKeys(true)) {
            Object value = config.getConfig().get(key);
            if (!(value instanceof ConfigurationSection)) prepared.set(key, inlineLegacyValue(value, legacy));
        }
        ConfigurationSection localized = languageService.localize(prepared);
        boolean changed = false;
        for (String key : localized.getKeys(true)) {
            Object value = localized.get(key);
            if (!(value instanceof ConfigurationSection) && !Objects.equals(config.getConfig().get(key), value)) {
                config.getConfig().set(key, value);
                changed = true;
            }
        }
        return changed;
    }

    private static Object inlineLegacyValue(Object value, YamlConfiguration legacy) {
        if (value instanceof List<?> list)
            return list.stream().map(line -> inlineLegacyValue(line, legacy)).toList();
        if (!(value instanceof String text)) return value;
        return LEGACY_REFERENCE.matcher(text).replaceAll(match -> {
            String template = legacy.getString(match.group(1), DEFAULT_LINE);
            for (ModelId model : ModelId.values())
                template = template.replace("[" + model.displayName() + "]", "[{model}]");
            if (!template.contains("{model}")) template = "&7[{model}] " + template;
            return Matcher.quoteReplacement(template);
        });
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
        Map<ModelId, String> names = new EnumMap<>(ModelId.class);
        for (ModelId model : ModelId.values()) {
            String name = config.getRawString("model_rotation.names." + model.key(), model.displayName());
            if (name.isBlank() || name.length() > 128)
                throw new IllegalArgumentException("Invalid hologram model name: " + model.key());
            names.put(model, StringColorize.parse(name));
        }
        modelNames = Map.copyOf(names);
    }

    public String getModelName(ModelId model) {
        return modelNames.get(model);
    }
}
