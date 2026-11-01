package ru.raveon.verification;

import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.config.datacollect.DataCollectConfigManager;
import ru.raveon.config.RuntimeConfiguration;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public final class ConfigurationVerification {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("raveon-config-test");
        Files.createDirectories(root.resolve("features"));
        for (String file : new String[] {"checks.yml", "features/datacollect.yml"}) {
            try (var input = ConfigurationVerification.class.getClassLoader().getResourceAsStream(file)) {
                Files.copy(input, root.resolve(file));
            }
        }
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDataFolder" -> root.toFile();
                    case "getLogger" -> Logger.getLogger("ConfigurationVerification");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ChecksConfigManager previous = new ChecksConfigManager(plugin);
        String valid = Files.readString(root.resolve("checks.yml"));
        Files.writeString(root.resolve("checks.yml"), valid.replace("step: 10", "step: 2147483647"));
        rejected(() -> new ChecksConfigManager(plugin));
        require(previous.getAnalysisSequence() == 50, "failed candidate changed previous values");
        Files.writeString(root.resolve("checks.yml"), valid.replace("probability: 0.85", "probability: .nan"));
        rejected(() -> new ChecksConfigManager(plugin));
        Files.writeString(root.resolve("checks.yml"), "analyze: [broken");
        rejected(() -> new ChecksConfigManager(plugin));
        require(previous.getAnalysisSequence() == 50, "malformed YAML changed previous values");
        String queryEndpoint = "http://127.0.0.1:8009/v1/inference?model=pro&count=1";
        Files.writeString(root.resolve("checks.yml"), valid.replace(previous.getAnalyzeServer(), queryEndpoint));
        require(new ChecksConfigManager(plugin).getAnalyzeServer().equals(queryEndpoint), "endpoint query was colorized");
        Files.writeString(root.resolve("checks.yml"), valid);
        String datasets = Files.readString(root.resolve("features/datacollect.yml"));
        Files.writeString(root.resolve("features/datacollect.yml"), datasets.replace("frames: 50", "frames: 0"));
        rejected(() -> new DataCollectConfigManager(plugin));
        Files.writeString(root.resolve("plain.yml"), "text: '%model%'\n");
        String unchanged = Files.readString(root.resolve("plain.yml"));
        CustomConfig custom = new CustomConfig("plain.yml", plugin);
        require(custom.getString("text", "").equals("%model%"), "unexpected placeholder migration");
        require(custom.getInt("missing", 7) == 7, "default lookup failed");
        require(Files.readString(root.resolve("plain.yml")).equals(unchanged), "read mutated configuration");
        verifyBundle(root.resolve("bundle"));
        System.out.println("ConfigurationVerification passed: malformed YAML, limits, NaN, immutable previous state, bundle reload, resource reuse and no file migration");
    }

    private static void verifyBundle(Path root) throws Exception {
        for (String file : new String[] {"checks.yml", "features/datacollect.yml",
                "features/hologram.yml", "features/punishments.yml", "settings.yml"}) {
            Path destination = root.resolve(file);
            Files.createDirectories(destination.getParent());
            try (var input = ConfigurationVerification.class.getClassLoader().getResourceAsStream(file)) {
                Files.copy(input, destination);
            }
        }
        Files.createDirectories(root.resolve("menu"));
        Files.writeString(root.resolve("menu/players.yml"), "title: Players\nsize: 54\n");
        Files.writeString(root.resolve("menu/history.yml"), "title: History\nsize: 54\n");
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDataFolder" -> root.toFile();
                    case "getLogger" -> Logger.getLogger("ConfigurationVerification");
                    case "getName" -> "ConfigurationVerification";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        String defaultSettings = Files.readString(root.resolve("settings.yml"));
        require(defaultSettings.contains("language: en"), "English is not the default");
        Files.writeString(root.resolve("settings.yml"), defaultSettings.replace("language: en", "language: ru"));
        RuntimeConfiguration previous = RuntimeConfiguration.load(plugin, null);
        try {
            RuntimeConfiguration unchanged = RuntimeConfiguration.load(plugin, previous);
            require(unchanged.main().getDatabaseManager() == previous.main().getDatabaseManager(),
                    "reload replaced live database resources");
            String checks = Files.readString(root.resolve("checks.yml"));
            String translation = Files.readString(root.resolve("lang/ru/translations.yml"));
            String settings = Files.readString(root.resolve("settings.yml"));
            Files.writeString(root.resolve("checks.yml"), checks.replace("step: 10", "step: 20"));
            Files.writeString(root.resolve("lang/ru/translations.yml"), "prefix: 7\n");
            rejected(() -> RuntimeConfiguration.load(plugin, previous));
            require(previous.checks().getAnalysisSequence() == 50,
                    "late bundle failure published earlier candidate values");
            Files.writeString(root.resolve("lang/ru/translations.yml"), translation);
            RuntimeConfiguration changed = RuntimeConfiguration.load(plugin, previous);
            require(changed.checks().getAnalysisStep() == 20, "valid bundle reload did not apply");
            require(previous.checks().getAnalysisSequence() == 50, "valid reload mutated previous bundle");
            require(changed.main().getDatabaseManager() == previous.main().getDatabaseManager(),
                    "valid bundle reload leaked database executor");
            Files.writeString(root.resolve("settings.yml"), settings.replace("language: ru", "language: en"));
            RuntimeConfiguration english = RuntimeConfiguration.load(plugin, changed);
            require(english.main().getLanguageService().language().equals("en"), "reload did not switch to English");
            require(english.main().getLanguageService().plain("reloaded").contains("Configuration"), "reload kept Russian messages");
            require(changed.main().getLanguageService().language().equals("ru"), "language reload mutated previous snapshot");
            require(english.main().getDatabaseManager() == changed.main().getDatabaseManager(), "language switch replaced database");
            Files.writeString(root.resolve("settings.yml"), settings.replace("name: 'database'", "name: 'other'"));
            rejected(() -> RuntimeConfiguration.load(plugin, changed));
            require(changed.main().getDatabaseManager().getTable().equals("database"),
                    "rejected database reconfiguration changed live storage");
        } finally {
            previous.main().getDatabaseManager().shutdown().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void rejected(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid candidate accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage() != null, "configuration failure has no message");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
