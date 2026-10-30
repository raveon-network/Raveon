package ru.raveon.service;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ru.raveon.utils.StringColorize;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class LanguageService {
    private static final Pattern REFERENCE = Pattern.compile("\\{lang:([a-zA-Z0-9_.-]+)}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");
    private final String language;
    private final Map<String, Object> raw;
    private final Map<String, Object> rendered;

    private LanguageService(String language, Map<String, Object> entries) {
        this.language = language;
        String prefix = (String) entries.getOrDefault("prefix", "");
        Map<String, Object> expanded = new LinkedHashMap<>();
        Map<String, Object> colored = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            if (value instanceof String text) {
                String template = text.replace("{prefix}", prefix);
                expanded.put(key, template);
                colored.put(key, StringColorize.parse(template));
            } else {
                List<String> lines = strings(value, key).stream()
                        .map(line -> line.replace("{prefix}", prefix)).toList();
                expanded.put(key, lines);
                colored.put(key, lines.stream().map(StringColorize::parse).toList());
            }
        });
        raw = Map.copyOf(expanded);
        rendered = Map.copyOf(colored);
    }

    public static LanguageService load(Plugin plugin, String requested) {
        String language = requested.trim().toLowerCase(Locale.ROOT);
        if (!language.equals("ru") && !language.equals("en"))
            throw new IllegalArgumentException("language must be ru or en");
        Path directory = plugin.getDataFolder().toPath().resolve("lang");
        try {
            Files.createDirectories(directory);
            for (String code : List.of("ru", "en")) {
                Path file = directory.resolve(code + ".yml");
                if (!Files.exists(file)) {
                    try (var input = resource(code)) {
                        Files.copy(input, file);
                    }
                }
            }
            Map<String, Object> entries = new LinkedHashMap<>();
            merge(entries, bundled("en"));
            if (!language.equals("en")) merge(entries, bundled(language));
            YamlConfiguration custom = new YamlConfiguration();
            try (var reader = Files.newBufferedReader(directory.resolve(language + ".yml"), StandardCharsets.UTF_8)) {
                custom.load(reader);
            }
            merge(entries, custom);
            return new LanguageService(language, entries);
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("Cannot load lang/" + language + ".yml", error);
        }
    }

    private static java.io.InputStream resource(String language) {
        var input = LanguageService.class.getClassLoader().getResourceAsStream("lang/" + language + ".yml");
        if (input == null) throw new IllegalStateException("Missing bundled language: " + language);
        return input;
    }

    private static YamlConfiguration bundled(String language) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (var reader = new InputStreamReader(resource(language), StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        return config;
    }

    private static void merge(Map<String, Object> entries, ConfigurationSection source) {
        for (String key : source.getKeys(true)) {
            Object value = source.get(key);
            Object previous = entries.get(key);
            if (value instanceof ConfigurationSection) {
                if (previous != null) throw new IllegalArgumentException("Language key must be text: " + key);
                continue;
            }
            if (value == null) continue;
            if (entries.keySet().stream().anyMatch(existing -> existing.startsWith(key + ".")))
                throw new IllegalArgumentException("Language key must be a section: " + key);
            Object checked = value instanceof String ? value : strings(value, key);
            if (previous != null && (previous instanceof String) != (checked instanceof String))
                throw new IllegalArgumentException("Language key has the wrong type: " + key);
            entries.put(key, checked);
        }
    }

    private static List<String> strings(Object value, String key) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)))
            throw new IllegalArgumentException("Language key must be text or a list of text: " + key);
        return list.stream().map(String.class::cast).toList();
    }

    public String language() {
        return language;
    }

    public String text(String key, Object... placeholders) {
        Object value = rendered.get(key);
        if (!(value instanceof String text))
            throw new IllegalArgumentException("Unknown text language key: " + key);
        return format(text, placeholders);
    }

    public String plain(String key, Object... placeholders) {
        return ChatColor.stripColor(text(key, placeholders));
    }

    public String format(String template, Object... placeholders) {
        if (placeholders.length % 2 != 0)
            throw new IllegalArgumentException("Placeholders require name/value pairs");
        if (placeholders.length == 0) return template;
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < placeholders.length; i += 2)
            values.put(String.valueOf(placeholders[i]), java.util.Objects.toString(placeholders[i + 1], ""));
        return PLACEHOLDER.matcher(template).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group())));
    }

    public ConfigurationSection localize(ConfigurationSection source) {
        YamlConfiguration result = new YamlConfiguration();
        for (String key : source.getKeys(true)) {
            Object value = source.get(key);
            if (!(value instanceof ConfigurationSection)) result.set(key, resolve(value));
        }
        return result;
    }

    private Object resolve(Object value) {
        if (value instanceof List<?> list) {
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            for (Object entry : list) {
                Object resolved = resolve(entry);
                if (resolved instanceof List<?> lines) result.addAll(lines);
                else result.add(resolved);
            }
            return List.copyOf(result);
        }
        if (!(value instanceof String text)) return value;
        var exact = REFERENCE.matcher(text);
        if (exact.matches()) return entry(exact.group(1));
        return REFERENCE.matcher(text).replaceAll(match -> {
            Object replacement = entry(match.group(1));
            if (!(replacement instanceof String line))
                throw new IllegalArgumentException("Cannot embed language list: " + match.group(1));
            return java.util.regex.Matcher.quoteReplacement(line);
        });
    }

    private Object entry(String key) {
        Object value = raw.get(key);
        if (value == null) throw new IllegalArgumentException("Unknown language reference: " + key);
        return value;
    }

    public String duration(long seconds) {
        long remaining = Math.max(0, seconds);
        long[] sizes = {31_536_000, 2_592_000, 86_400, 3_600, 60, 1};
        String[] units = {"year", "month", "day", "hour", "minute", "second"};
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            long count = remaining / sizes[i];
            remaining %= sizes[i];
            if (count > 0 || (i == sizes.length - 1 && parts.isEmpty()))
                parts.add(text("time." + units[i], "value", count));
        }
        return String.join(" ", parts);
    }
}
