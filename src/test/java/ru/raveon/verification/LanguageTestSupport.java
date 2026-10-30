package ru.raveon.verification;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ru.raveon.config.MainConfigManager;
import ru.raveon.service.LanguageService;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Logger;

final class LanguageTestSupport {
    private static final Map<MainConfigManager, Path> folders = new IdentityHashMap<>();

    static Plugin plugin(Path folder) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDataFolder" -> folder.toFile();
                    case "getLogger" -> Logger.getLogger("LanguageVerification");
                    case "getName" -> "LanguageVerification";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    static void install(MainConfigManager config, Map<String, String> messages) throws Exception {
        Path folder = Files.createTempDirectory("raveon-language-fixture");
        folders.put(config, folder);
        YamlConfiguration custom = new YamlConfiguration();
        messages.forEach(custom::set);
        save(folder, custom);
        publish(config, folder);
    }

    static void update(MainConfigManager config, String key, String value) throws Exception {
        if (!folders.containsKey(config)) install(config, Map.of());
        Path folder = folders.get(config);
        YamlConfiguration custom = new YamlConfiguration();
        try (var reader = Files.newBufferedReader(folder.resolve("lang/en.yml"), StandardCharsets.UTF_8)) {
            custom.load(reader);
        }
        custom.set(key, value);
        save(folder, custom);
        publish(config, folder);
    }

    private static void save(Path folder, YamlConfiguration custom) throws Exception {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/en.yml"), custom.saveToString(), StandardCharsets.UTF_8);
    }

    private static void publish(MainConfigManager config, Path folder) throws Exception {
        var field = MainConfigManager.class.getDeclaredField("languageService");
        field.setAccessible(true);
        field.set(config, LanguageService.load(plugin(folder), "en"));
    }
}
