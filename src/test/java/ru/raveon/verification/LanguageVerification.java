package ru.raveon.verification;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.raveon.service.LanguageService;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class LanguageVerification {
    private static final Pattern PLACEHOLDERS = Pattern.compile("\\{[a-zA-Z0-9_]+}");

    public static void main(String[] args) throws Exception {
        YamlConfiguration english = bundled("lang/en.yml");
        YamlConfiguration russian = bundled("lang/ru.yml");
        Set<String> keys = leafKeys(english);
        require(keys.equals(leafKeys(russian)), "language catalogs have different keys");
        for (String key : keys) {
            Object en = english.get(key);
            Object ru = russian.get(key);
            require((en instanceof String) == (ru instanceof String), "different value types: " + key);
            require(placeholders(en).equals(placeholders(ru)), "different placeholders: " + key);
            require(!Pattern.compile("[\\u0400-\\u04ff]").matcher(en.toString()).find(),
                    "English contains untranslated Cyrillic text: " + key);
        }

        Path folder = Files.createTempDirectory("raveon-language-test");
        var plugin = LanguageTestSupport.plugin(folder);
        LanguageService ru = LanguageService.load(plugin, " RU ");
        LanguageService en = LanguageService.load(plugin, "en");
        require(ru.language().equals("ru") && en.language().equals("en"), "language selection");
        require(Files.isRegularFile(folder.resolve("lang/ru.yml"))
                && Files.isRegularFile(folder.resolve("lang/en.yml")), "catalogs not installed");
        require(en.plain("alerts-enabled").contains("Alerts"), "English message missing");
        require(ru.plain("alerts-enabled").contains("Оповещения"), "Russian message missing");
        require(en.text("alerts-enabled").contains("§"), "colors were not rendered");
        require(!en.plain("alerts-enabled").contains("§"), "plain message contains colors");
        require(en.duration(3661).equals("1 h 1 min 1 sec"), "English duration");
        require(ru.duration(3661).equals("1 ч 1 мин 1 сек"), "Russian duration");
        require(en.duration(-1).equals("0 sec"), "negative duration");

        String replacement = "$1\\{model}&c";
        require(en.format("{player}|{model}", "player", replacement, "model", "Pro")
                .equals(replacement + "|Pro"), "replacement interpreted or substituted twice");
        rejected(() -> en.format("x", "missing-value"));
        rejected(() -> LanguageService.load(plugin, "../en"));
        rejected(() -> LanguageService.load(plugin, "de"));
        rejected(() -> en.text("missing.key"));

        YamlConfiguration source = new YamlConfiguration();
        source.set("title", "{lang:menus.history.title}");
        source.set("lore", "{lang:menus.players.player.lore}");
        source.set("commands", List.of("ban {player} {lang:punishments.reason}"));
        source.set("size", 54);
        ConfigurationSection localized = ru.localize(source);
        require(localized.getString("title").contains("история"), "title was not localized");
        require(localized.getStringList("lore").stream().anyMatch(line -> line.contains("Последние")),
                "list reference was not expanded");
        require(localized.getStringList("commands").get(0).startsWith("ban {player} "),
                "command or runtime placeholder changed");
        require(localized.getInt("size") == 54, "structural configuration changed");
        require(source.getString("title").equals("{lang:menus.history.title}"),
                "localization mutated the source configuration");
        source.set("title", "{lang:missing.key}");
        rejected(() -> ru.localize(source));
        source.set("title", "prefix {lang:menus.players.player.lore}");
        rejected(() -> ru.localize(source));

        for (String name : List.of("menu/players.yml", "menu/history.yml", "features/hologram.yml",
                "features/punishments.yml")) {
            YamlConfiguration settings = bundled(name);
            for (LanguageService language : List.of(ru, en)) {
                ConfigurationSection translated = language.localize(settings);
                require(translated.getValues(true).values().stream()
                        .filter(value -> !(value instanceof ConfigurationSection))
                        .noneMatch(value -> value.toString().contains("{lang:")), "unresolved reference: " + name);
            }
        }

        Path file = folder.resolve("lang/ru.yml");
        YamlConfiguration custom = new YamlConfiguration();
        custom.set("prefix", "[Custom]");
        custom.set("reloaded", "{prefix} готово: {player}");
        Files.writeString(file, custom.saveToString(), StandardCharsets.UTF_8);
        String saved = Files.readString(file);
        LanguageService edited = LanguageService.load(plugin, "ru");
        require(edited.text("reloaded", "player", "Alex").equals("[Custom] готово: Alex"),
                "custom message or prefix ignored");
        require(edited.plain("history-no-data").contains("не найдены"), "missing key did not use Russian default");
        require(Files.readString(file).equals(saved), "custom catalog was overwritten");
        require(ru.plain("reloaded").contains("Конфигурация"), "old snapshot was mutated");
        require(en.plain("reloaded").contains("Configuration"), "English snapshot was mutated");
        Files.writeString(file, "prefix: 7\n");
        rejected(() -> LanguageService.load(plugin, "ru"));
        Files.writeString(file, "monitor: wrong-type\n");
        rejected(() -> LanguageService.load(plugin, "ru"));
        Files.writeString(file, "menus:\n  players:\n    player:\n      lore: 7\n");
        rejected(() -> LanguageService.load(plugin, "ru"));
        Files.writeString(file, "prefix: [broken");
        rejected(() -> LanguageService.load(plugin, "ru"));
        require(edited.text("reloaded", "player", "Alex").equals("[Custom] готово: Alex"),
                "failed reload changed the active snapshot");
        System.out.println("LanguageVerification passed: ru/en parity, placeholders, menus, commands, UTF-8, fallback, validation and immutable reload");
    }

    private static YamlConfiguration bundled(String path) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (var input = LanguageVerification.class.getClassLoader().getResourceAsStream(path)) {
            require(input != null, "missing bundled resource: " + path);
            config.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return config;
    }

    private static Set<String> leafKeys(ConfigurationSection config) {
        Set<String> keys = new HashSet<>();
        for (String key : config.getKeys(true))
            if (!config.isConfigurationSection(key)) keys.add(key);
        return keys;
    }

    private static Set<String> placeholders(Object value) {
        var matcher = PLACEHOLDERS.matcher(value.toString());
        Set<String> result = new HashSet<>();
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static void rejected(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid language configuration accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage() != null, "validation error has no message");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
