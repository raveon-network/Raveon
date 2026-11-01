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

    public static final List<String> CATALOG_FILES = List.of(
            "history.yml", "players.yml", "datacollect.yml", "hologram.yml", "translations.yml");

    public static LanguageService load(Plugin plugin, String requested) {
        String language = requested.trim().toLowerCase(Locale.ROOT);
        if (!language.equals("ru") && !language.equals("en"))
            throw new IllegalArgumentException("language must be ru or en");
        Path directory = plugin.getDataFolder().toPath().resolve("lang");
        try {
            for (String code : List.of("ru", "en")) {
                Path folder = directory.resolve(code);
                Files.createDirectories(folder);
                Path legacyFile = directory.resolve(code + ".yml");
                YamlConfiguration legacy = null;
                if (Files.exists(legacyFile)
                        && CATALOG_FILES.stream().anyMatch(name -> !Files.exists(folder.resolve(name))))
                    legacy = read(legacyFile);
                for (String name : CATALOG_FILES) {
                    Path file = folder.resolve(name);
                    if (Files.exists(file)) continue;
                    if (legacy == null) {
                        try (var input = resource(code, name)) {
                            Files.copy(input, file);
                        }
                    } else {
                        YamlConfiguration migrated = bundled(code, name);
                        for (String key : legacy.getKeys(true)) {
                            if (legacy.isConfigurationSection(key) || key.startsWith("punishments.")
                                    || !catalogFor(key).equals(name)) continue;
                            Object value = legacy.get(key);
                            if (!obsoleteHistoryDefault(key, value)) migrated.set(key, value);
                        }
                        migrated.save(file.toFile());
                    }
                }
            }
            for (String code : List.of("ru", "en")) migrateMenuLabels(directory.resolve(code), code);
            Map<String, Object> entries = new LinkedHashMap<>();
            for (String name : CATALOG_FILES) merge(entries, bundled("en", name));
            if (!language.equals("en"))
                for (String name : CATALOG_FILES) merge(entries, bundled(language, name));
            for (String name : CATALOG_FILES)
                merge(entries, read(directory.resolve(language).resolve(name)));
            return new LanguageService(language, entries);
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("Cannot load lang/" + language + "/", error);
        }
    }

    private static void migrateMenuLabels(Path folder, String code)
            throws IOException, InvalidConfigurationException {
        Path translationsFile = folder.resolve("translations.yml");
        YamlConfiguration translations = read(translationsFile);
        boolean changed = false;
        for (String menu : List.of("history", "players")) {
            Path file = folder.resolve(menu + ".yml");
            YamlConfiguration custom = read(file);
            YamlConfiguration defaults = bundled(code, menu + ".yml");
            boolean updated = false;
            for (String key : List.of("model", "refresh", "previous", "next", "back")) {
                String shared = "menus." + key;
                String scoped = "menus." + menu + "." + key;
                if (translations.contains(shared)
                        && (!custom.contains(scoped) || java.util.Objects.equals(custom.get(scoped), defaults.get(scoped)))) {
                    custom.set(scoped, translations.get(shared));
                    updated = true;
                }
            }
            if (updated) custom.save(file.toFile());
        }
        for (String key : List.of("model", "refresh", "previous", "next", "back")) {
            if (translations.contains("menus." + key)) {
                translations.set("menus." + key, null);
                changed = true;
            }
        }
        ConfigurationSection remaining = translations.getConfigurationSection("menus");
        if (remaining != null && remaining.getKeys(false).isEmpty()) translations.set("menus", null);
        if (changed) translations.save(translationsFile.toFile());
    }

    private static String catalogFor(String key) {
        if (key.startsWith("menus.history.") || key.startsWith("history-")) return "history.yml";
        if (key.startsWith("menus.players.")) return "players.yml";
        if (key.startsWith("datacollect.")) return "datacollect.yml";
        if (key.startsWith("holograms.") || key.startsWith("holograms-")) return "hologram.yml";
        return "translations.yml";
    }

    private static boolean obsoleteHistoryDefault(String key, Object value) {
        return switch (key) {
            case "menus.history.snapshot.name" -> "&fAverage: {snapshot_avg}%".equals(value)
                    || "&fСреднее: {snapshot_avg}%".equals(value);
            case "menus.history.snapshot.lore" -> List.of("", "&7Prob. &8&l| &7Server      &8&l| &7Time",
                    "&8-------------------------------", "{entries}").equals(value)
                    || List.of("", "&7Вероят. &8&l| &7Сервер     &8&l| &7Время",
                    "&8-------------------------------", "{entries}").equals(value);
            case "menus.history.entry" -> "{chance}% &8&l| &f{server}&8&l| &f{time}".equals(value);
            default -> false;
        };
    }

    private static YamlConfiguration read(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        return config;
    }

    private static java.io.InputStream resource(String language, String name) {
        String path = "lang/" + language + "/" + name;
        var input = LanguageService.class.getClassLoader().getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("Missing bundled language: " + path);
        return input;
    }

    private static YamlConfiguration bundled(String language, String name)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (var reader = new InputStreamReader(resource(language, name), StandardCharsets.UTF_8)) {
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

    public ConfigurationSection localizeMenu(ConfigurationSection source, String menu) {
        if (!menu.equals("history") && !menu.equals("players"))
            throw new IllegalArgumentException("Unknown menu: " + menu);
        YamlConfiguration scoped = new YamlConfiguration();
        for (String key : source.getKeys(true)) {
            Object value = source.get(key);
            if (!(value instanceof ConfigurationSection)) scoped.set(key, scopeMenu(value, menu));
        }
        return localize(scoped);
    }

    private static Object scopeMenu(Object value, String menu) {
        if (value instanceof List<?> lines) return lines.stream().map(line -> scopeMenu(line, menu)).toList();
        if (!(value instanceof String text)) return value;
        for (String key : List.of("model", "refresh", "previous", "next", "back"))
            text = text.replace("{lang:menus." + key + "}", "{lang:menus." + menu + "." + key + "}");
        return text;
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
