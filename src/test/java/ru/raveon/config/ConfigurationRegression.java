package ru.raveon.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.config.anticheat.HologramConfigManager;
import ru.raveon.config.anticheat.PunishmentConfigManager;
import ru.raveon.config.datacollect.DataCollectConfigManager;
import ru.raveon.model.CloudPunishments;
import ru.raveon.model.ModelId;
import ru.raveon.utils.TimeUtils;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class ConfigurationRegression {
    private static final ClassLoader RESOURCES = ConfigurationRegression.class.getClassLoader();

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("raveon-english-config-");
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, parameters) -> switch (method.getName()) {
                    case "getDataFolder" -> directory.toFile();
                    case "getResource" -> RESOURCES.getResourceAsStream((String) parameters[0]);
                    case "saveResource" -> {
                        Path destination = directory.resolve((String) parameters[0]);
                        Files.createDirectories(destination.getParent());
                        try (var input = RESOURCES.getResourceAsStream((String) parameters[0])) {
                            if (input == null) throw new AssertionError("missing resource " + parameters[0]);
                            Files.copy(input, destination);
                        }
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var messages = new CustomConfig("translation.yml", plugin);
        String formatted = messages.message("connect.success", "server", "$server\\name", "models", "Test");
        check(formatted.contains("$server\\name") && formatted.contains("Test") && !formatted.contains("{prefix}"),
                "prefix and external values must format safely");
        check(ChatColor.stripColor(messages.message("errors.no-permission")).contains("permission"), "messages must be English");
        var legacyMessages = new YamlConfiguration();
        legacyMessages.set("prefix", "&aCustom");
        legacyMessages.set("reloaded", "{prefix} custom reload");
        legacyMessages.save(directory.resolve("translation.yml").toFile());
        messages = new CustomConfig("translation.yml", plugin);
        check(ChatColor.stripColor(messages.message("reloaded")).equals("Custom custom reload"), "custom messages must survive");
        check(messages.message("connect.usage").contains("connect"), "new keys must fall back to English defaults");

        Files.createDirectories(directory.resolve("menu"));
        var oldMenu = new YamlConfiguration();
        oldMenu.set("title", "Custom title");
        oldMenu.set("settings.snapshot_form.lore", "{lang:menus.history.snapshot.lore}");
        oldMenu.set("items.back-item.display_name", "{lang:menus.history.back}");
        oldMenu.save(directory.resolve("menu/history.yml").toFile());
        var history = new CustomConfig("menu/history.yml", plugin);
        check(history.getRawString("title", "").equals("Custom title"), "literal menu customizations must survive");
        check(history.getConfig().getStringList("settings.snapshot_form.lore").size() == 2, "legacy lore must become a literal list");
        check(!history.getRawString("items.back-item.display_name", "").contains("{lang:"), "legacy menu references must be removed");
        check(Files.exists(directory.resolve("menu/history.yml.before-english.bak")), "old menu must be backed up");
        new CustomConfig("menu/history.yml", plugin);

        Files.createDirectories(directory.resolve("features"));
        Files.writeString(directory.resolve("features/datacollect.yml"), "frames: 50\ninfo_statistic:\n  type: BOSSBAR\n");
        var datasets = new DataCollectConfigManager(plugin);
        check(datasets.getInfoLine().contains("{player}") && !datasets.getInfoLine().contains("{model}"), "statistics must not show a model label");
        check(datasets.message("start.start").contains("Started"), "feature messages must load without language catalogs");
        var holograms = new YamlConfiguration();
        holograms.set("lines", List.of("Custom heading", "{lang:holograms.pro}"));
        holograms.save(directory.resolve("features/hologram.yml").toFile());
        var displays = new HologramConfigManager(plugin);
        check(displays.getLines().get(0).equals("Custom heading") && displays.getLines().stream().noneMatch(s -> s.contains("{lang:")), "hologram text must be literal");
        new PunishmentConfigManager(plugin, CloudPunishments.OFF, Set.of(ModelId.values()));

        check(TimeUtils.formatSeconds(0).equals("0 sec") && TimeUtils.formatSeconds(-10).equals("0 sec"), "zero and negative durations");
        check(TimeUtils.formatSeconds(490).equals("8 min 10 sec"), "English duration formatting");
        check(TimeUtils.formatSeconds(31536000).equals("1 yr"), "large duration formatting");

        YamlConfiguration common = new CustomConfig("translation.yml", plugin).getConfig() instanceof YamlConfiguration yaml ? yaml : null;
        check(common != null, "configuration type");
        var feature = new CustomConfig("features/datacollect.yml", plugin).getConfig();
        var pattern = Pattern.compile("\\.(?:message|plainMessage)\\(\"([^\"]+)\"(?=\\s*[,\\)])");
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = pattern.matcher(Files.readString(source));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    check(common.isString(key) || feature.isString("messages." + key), "unresolved message key " + key + " in " + source);
                }
            }
        }
        try (var resources = Files.walk(Path.of("src/main/resources"))) {
            for (Path resource : resources.filter(Files::isRegularFile).toList()) {
                String value = Files.readString(resource);
                check(!value.contains("{lang:"), "bundled language reference remains: " + resource);
                check(!Pattern.compile("[\\u0400-\\u04ff]").matcher(value).find(), "non-English bundled text: " + resource);
            }
        }
        check(!Files.exists(Path.of("src/main/java/ru/raveon/service/LanguageService.java")), "language service must be removed");
        System.out.println("ConfigurationRegression: English messages, migration, feature configs and all message keys passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
