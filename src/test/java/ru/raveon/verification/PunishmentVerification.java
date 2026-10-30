package ru.raveon.verification;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import ru.raveon.api.database.DatabaseManager;
import ru.raveon.api.database.DatabaseType;
import ru.raveon.config.anticheat.PunishmentConfigManager;
import ru.raveon.config.anticheat.PunishmentConfigManager.PunishGroupConfig;
import ru.raveon.database.modules.PunishmentDatabase;
import ru.raveon.manager.anticheat.PunishmentManager;
import ru.raveon.model.ModelId;
import ru.raveon.service.LanguageService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static ru.raveon.verification.MultimodelVerification.require;

public final class PunishmentVerification {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("raveon-punishments");
        Files.createDirectories(folder.resolve("features"));
        Plugin plugin = LanguageTestSupport.plugin(folder);
        LanguageService language = LanguageService.load(plugin, "en");
        String resource;
        try (var input = PunishmentVerification.class.getClassLoader().getResourceAsStream("features/punishments.yml")) {
            resource = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Files.writeString(folder.resolve("features/punishments.yml"), resource);
        var config = new PunishmentConfigManager(plugin, language);
        var group = config.getPunishGroups().get(0);
        require(group.matches("AimAI", ModelId.PRO) && group.matches("AimAI-Ultra", ModelId.ULTRA),
                "default group does not combine models");
        require(group.actions(4).stream().noneMatch(action -> action.startsWith("ban ")), "ban before VL 5");
        require(group.actions(5).stream().filter(action -> action.startsWith("ban ")).count() == 1,
                "original ban at VL 5 changed");
        require(group.actions(6).stream().noneMatch(action -> action.startsWith("ban ")), "one-shot ban repeated");
        require(group.actions(5).contains("[ai_alert]") && group.actions(5).contains("[log]"),
                "original observers changed");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(resource);
        yaml.set("aim_ai.models", List.of("pro"));
        yaml.set("aim_ai.stages.1", List.of("kick {player} suspicious"));
        yaml.set("aim_ai.stages.3", List.of("tempban {player} 1h"));
        yaml.save(folder.resolve("features/punishments.yml").toFile());
        var staged = new PunishmentConfigManager(plugin, language).getPunishGroups().get(0);
        require(staged.matches("AimAI", ModelId.PRO) && !staged.matches("AimAI-Ultra", ModelId.ULTRA),
                "source model filter ignored");
        require(staged.actions(2).contains("kick {player} suspicious"), "stage did not persist until next threshold");
        require(staged.actions(3).contains("tempban {player} 1h"), "next stage not selected");
        require(!staged.actions(3).contains("kick {player} suspicious"), "lower stage ran with higher stage");
        for (var entry : Map.<String, Object>of(
                "aim_ai.models", List.of("unknown"), "aim_ai.commands", "bad",
                "aim_ai.stages", List.of("bad"), "aim_ai.remove-violations-after", "forever").entrySet()) {
            YamlConfiguration invalid = new YamlConfiguration();
            invalid.loadFromString(resource);
            invalid.set(entry.getKey(), entry.getValue());
            invalid.save(folder.resolve("features/punishments.yml").toFile());
            try {
                new PunishmentConfigManager(plugin, language);
                throw new AssertionError("invalid punishment settings accepted: " + entry.getKey());
            } catch (IllegalArgumentException expected) {}
        }

        var flag = new PunishmentManager.Flag(UUID.randomUUID(), "Player", "AimAI", 2,
                ModelId.ULTRA, .95, 75, "detail");
        require(PunishmentManager.format(
                "{model}|{group}|{vl}|{check_vl}|{violations}|{total_violations}|{buffer}",
                flag, "aim_ai", 3, 5).equals("Ultra|aim_ai|5|2|3|5|75.00"),
                "source/group placeholders mixed");
        verifyDatabase(plugin, folder, group);
        System.out.println("PunishmentVerification passed: combined/split groups, preserved commands, stages, durable SQLite VL, expiry, concurrency, rollback and failure");
    }

    private static void verifyDatabase(Plugin plugin, Path folder, PunishGroupConfig group) throws Exception {
        AtomicLong time = new AtomicLong(1_000_000);
        DatabaseManager manager = manager(plugin, folder);
        PunishmentDatabase storage = new PunishmentDatabase(manager, "test-server", time::get);
        UUID player = UUID.randomUUID();
        try {
            for (int i = 1; i <= 5; i++) {
                ModelId model = i % 2 == 1 ? ModelId.PRO : ModelId.ULTRA;
                var counted = storage.record(player, model, model.key(), List.of(group), time.get())
                        .get(5, TimeUnit.SECONDS).get(0);
                require(counted.total() == i, "models did not increment one group: " + i);
                if (i == 5) require(counted.checkViolations() == 3, "per-model count mixed");
            }
            manager.shutdown().get(5, TimeUnit.SECONDS);
            manager = manager(plugin, folder);
            storage = new PunishmentDatabase(manager, "test-server", time::get);
            require(storage.record(player, ModelId.PRO, "pro", List.of(group), time.get())
                    .get(5, TimeUnit.SECONDS).get(0).total() == 6, "restart/reconnect erased VL");
            var otherServer = new PunishmentDatabase(manager, "other-server", time::get);
            require(otherServer.record(player, ModelId.PRO, "pro", List.of(group), time.get())
                    .get(5, TimeUnit.SECONDS).get(0).total() == 1, "server counters leaked");

            var independent = new PunishGroupConfig("independent", group.checks(), group.models(),
                    group.commands(), group.stages(), group.removeViolationsAfterMillis());
            var counts = storage.record(player, ModelId.PRO, "pro", List.of(group, independent), time.get())
                    .get(5, TimeUnit.SECONDS);
            require(counts.get(0).total() == 7 && counts.get(1).total() == 1,
                    "group names did not isolate counters");

            UUID concurrent = UUID.randomUUID();
            List<CompletableFuture<List<PunishmentDatabase.CountedGroup>>> writes = new ArrayList<>();
            for (int i = 0; i < 50; i++)
                writes.add(storage.record(concurrent, ModelId.PRO, "pro", List.of(group), time.get()));
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            List<Long> totals = writes.stream().map(f -> f.join().get(0).total()).sorted().toList();
            for (int i = 0; i < 50; i++) require(totals.get(i) == i + 1, "concurrent flag lost or counted twice");

            var broken = new PunishGroupConfig(null, List.of(), Set.of(ModelId.PRO),
                    List.of(), new TreeMap<>(), group.removeViolationsAfterMillis());
            UUID rollback = UUID.randomUUID();
            require(storage.record(rollback, ModelId.PRO, "pro", List.of(group, broken), time.get())
                    .handle((value, error) -> error != null).get(5, TimeUnit.SECONDS), "SQL failure hidden");
            require(storage.record(rollback, ModelId.PRO, "pro", List.of(group), time.get())
                    .get(5, TimeUnit.SECONDS).get(0).total() == 1, "partial group transaction survived rollback");

            long old = time.get();
            time.addAndGet(group.removeViolationsAfterMillis());
            require(storage.record(player, ModelId.PRO, "pro", List.of(group), time.get())
                    .get(5, TimeUnit.SECONDS).get(0).total() == 1, "expired flags count at TTL boundary");
            require(storage.record(player, ModelId.PRO, "pro", List.of(group), old)
                    .get(5, TimeUnit.SECONDS).isEmpty(), "expired queued flag accepted");
            manager.shutdown().get(5, TimeUnit.SECONDS);
            require(storage.record(player, ModelId.PRO, "pro", List.of(group), time.get())
                    .handle((value, error) -> error != null).get(5, TimeUnit.SECONDS), "closed DB accepted flag");
        } finally {
            manager.shutdown().get(5, TimeUnit.SECONDS);
        }
    }

    private static DatabaseManager manager(Plugin plugin, Path folder) {
        return new DatabaseManager(plugin, "", 3306, "", "", "punishment-test", DatabaseType.SQLITE,
                "jdbc:sqlite:" + folder.resolve("punishment-test.db"));
    }
}
