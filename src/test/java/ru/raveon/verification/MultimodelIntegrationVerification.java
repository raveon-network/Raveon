package ru.raveon.verification;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import ru.raveon.Raveon;
import ru.raveon.checks.data.UltraData;
import ru.raveon.config.RuntimeConfiguration;
import ru.raveon.database.modules.PunishmentDatabase;
import ru.raveon.integration.customplaceholder.PlaceholderIntegration;
import ru.raveon.listeners.bukkit.MitigationListener;
import ru.raveon.manager.alert.AlertManager;
import ru.raveon.manager.alert.ViolationManager;
import ru.raveon.manager.analytic.MonitorManager;
import ru.raveon.manager.anticheat.CheckManager;
import ru.raveon.manager.anticheat.PlayerDataManager;
import ru.raveon.manager.anticheat.PunishmentManager;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static ru.raveon.verification.MultimodelVerification.close;
import static ru.raveon.verification.MultimodelVerification.require;

public final class MultimodelIntegrationVerification {
    private static final List<String> commands = new ArrayList<>();
    private static final List<String> alerts = new ArrayList<>();
    private static final ConcurrentLinkedQueue<Runnable> scheduled = new ConcurrentLinkedQueue<>();
    private static Raveon plugin;
    private static Plugin configurationPlugin;
    private static Path folder;
    private static String checks;
    private static boolean owned = true;

    public static void main(String[] args) throws Exception {
        setup();
        try {
            verifyDefaultPipeline();
            verifyActionFailure();
            verifyRoles();
            verifySourceSnapshot();
            verifyEnableGates();
            verifyMitigationEvent();
            verifyReloadCancellation();
            verifyCombatBoundary();
            verifyConnectionOwnership();
            verifyModelHistory();
            verifyDatabaseFailure();
        } finally {
            plugin.getMainConfigManager().getDatabaseManager().shutdown().get(5, TimeUnit.SECONDS);
        }
        System.out.println("MultimodelIntegrationVerification passed: real AimAI to SQLite to commands, actions, inheritance, source attribution, enable gates, reload/reconnect, damage events and DB failure");
    }

    private static void setup() throws Exception {
        folder = Files.createTempDirectory("raveon-multimodel-integration");
        for (String name : List.of("checks.yml", "settings.yml", "features/datacollect.yml",
                "features/hologram.yml", "features/punishments.yml",
                "menu/players.yml", "menu/history.yml")) {
            Path file = folder.resolve(name);
            Files.createDirectories(file.getParent());
            try (var input = MultimodelIntegrationVerification.class.getClassLoader().getResourceAsStream(name)) {
                Files.copy(input, file);
            }
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(folder.resolve("settings.yml").toFile());
        yaml.set("language", "en");
        yaml.save(folder.resolve("settings.yml").toFile());
        Files.createDirectories(folder.resolve("lang/en"));
        yaml = new YamlConfiguration();
        yaml.set("ai-alert", "{model}|{probability}|{vl}|{buffer}");
        yaml.save(folder.resolve("lang/en/translations.yml").toFile());
        yaml = YamlConfiguration.loadConfiguration(folder.resolve("checks.yml").toFile());
        yaml.set("analyze.enabled", true);
        yaml.set("analyze.buffer.primary-multiplier", 1);
        yaml.set("analyze.worldguard.enabled", false);
        yaml.set("analyze.models.pro.probability", .5);
        yaml.set("analyze.models.ultra.probability", .5);
        yaml.save(folder.resolve("checks.yml").toFile());
        checks = yaml.saveToString();
        Files.writeString(folder.resolve("menu/players.yml"), "title: Players\nsize: 54\n");
        Files.writeString(folder.resolve("menu/history.yml"), "title: History\nsize: 54\n");
        configurationPlugin = LanguageTestSupport.plugin(folder);
        var task = proxy(org.bukkit.scheduler.BukkitTask.class, (p, m, a) -> defaultValue(m.getReturnType()));
        var scheduler = proxy(org.bukkit.scheduler.BukkitScheduler.class, (p, m, a) -> {
            if (m.getName().equals("runTask")) {
                scheduled.add((Runnable) a[1]);
                return task;
            }
            return defaultValue(m.getReturnType());
        });
        var console = proxy(org.bukkit.command.ConsoleCommandSender.class,
                (p, m, a) -> defaultValue(m.getReturnType()));
        Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
            case "getScheduler" -> scheduler;
            case "getPlayer", "getOfflinePlayer" -> {
                var player = plugin == null || !(a[0] instanceof UUID id) ? null
                        : plugin.getPlayerDataManager().getPlayer(id);
                yield player == null ? null : player.getBukkitPlayer();
            }
            case "getConsoleSender" -> console;
            case "isPrimaryThread" -> owned;
            case "dispatchCommand" -> {
                if (((String) a[1]).startsWith("broken ")) throw new IllegalStateException("Injected command error");
                if (((String) a[1]).startsWith("missing ")) yield false;
                commands.add((String) a[1]);
                yield true;
            }
            case "getName", "getVersion" -> "fixture";
            case "getBukkitVersion" -> "1.20.1-R0.1";
            case "getLogger" -> Logger.getLogger("MultimodelIntegration");
            default -> defaultValue(m.getReturnType());
        });
        field(Bukkit.class, "server").set(null, server);
        plugin = allocate(Raveon.class);
        set(plugin, "isEnabled", true);
        set(plugin, "logger", Logger.getLogger("MultimodelIntegration"));
        set(plugin, "description", new PluginDescriptionFile("Raveon", "2.0.2", "ru.raveon.Raveon"));
        set(plugin, "placeholderIntegration", proxy(PlaceholderIntegration.class,
                (p, m, a) -> m.getReturnType() == String.class ? "fixture" : null));
        Raveon.INSTANCE = plugin;
        set(plugin, "configuration", RuntimeConfiguration.load(configurationPlugin, null));
        set(plugin, "playerDataManager", new PlayerDataManager());
        set(plugin, "playerOnlineService", new ru.raveon.service.PlayerOnlineService(null));
        set(plugin, "monitorManager", new MonitorManager(plugin));
        set(plugin, "alertManager", new AlertManager(plugin::getMainConfigManager) {
            @Override public void sendAlert(String message) { alerts.add(message); }
        });
        set(plugin, "violationManager", new ViolationManager());
        var storage = new PunishmentDatabase(plugin.getMainConfigManager().getDatabaseManager(), "fixture");
        set(plugin, "punishmentManager", new PunishmentManager(plugin, storage));
    }

    private static void verifyDefaultPipeline() throws Exception {
        var player = player(UUID.randomUUID());
        for (ModelId model : List.of(ModelId.PRO, ModelId.ULTRA)) {
            answer(player, model);
            answer(player, model);
        }
        answer(player, ModelId.PRO);
        answer(player, ModelId.ULTRA);
        answer(player, ModelId.PRO);
        settle();
        require(commands.size() == 1 && commands.get(0).startsWith("ban Player "), "mixed models did not ban at common VL 5");
        require(alerts.size() == 5, "duplicate or missing alerts");
        for (int i = 0; i < 5; i++)
            require(alerts.get(i).split("\\|")[2].equals(Integer.toString(i + 1)), "wrong group VL in alert");
        long generation = player.getCheckManager().getGeneration(ModelId.PRO);
        reload(Map.of());
        player.reload();
        require(player.getCheckManager().getGeneration(ModelId.PRO) > generation, "reload kept old request generation");
        answer(player, ModelId.PRO);
        settle();
        require(alerts.get(5).contains("|6|"), "reload reset group VL");
        RaveonPlayer reconnected = player(player.getUuid());
        answer(reconnected, ModelId.PRO);
        answer(reconnected, ModelId.PRO);
        settle();
        require(alerts.get(6).contains("|7|") && commands.size() == 1, "reconnect reset VL or repeated one-shot ban");
    }

    private static void verifyActionFailure() throws Exception {
        Path file = folder.resolve("features/punishments.yml");
        String original = Files.readString(file);
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(original);
            yaml.set("aim_ai.commands", List.of(
                    "1:1 broken {player}", "1:1 missing {player}", "1:1 ban {player} reason"));
            yaml.save(file.toFile());
            reload(Map.of());
            var player = player(UUID.randomUUID());
            commands.clear();
            alerts.clear();
            answer(player, ModelId.PRO);
            answer(player, ModelId.PRO);
            settle();
            require(commands.equals(List.of("ban Player reason")), "broken action suppressed subsequent commands");
        } finally {
            Files.writeString(file, original);
        }
    }

    private static void verifyRoles() throws Exception {
        reload(Map.of("analyze.models.pro.inherit-buffer", "ultra",
                "analyze.models.ultra.actions.punish", false));
        var player = player(UUID.randomUUID());
        commands.clear();
        alerts.clear();
        for (int i = 0; i < 6; i++) answer(player, ModelId.PRO);
        settle();
        require(commands.size() == 1 && alerts.size() == 5, "buffer owner's actions overrode source");
        require(alerts.stream().allMatch(text -> text.startsWith("Pro|100|")), "source model attribution lost");
        close(player.getCheckManager().getAimAI(ModelId.ULTRA).getLastProbability(), 0,
                "inheriting result overwrote owner's probability");
        close(player.getCheckManager().getAimAI(ModelId.ULTRA).getBuffer(), 25, "shared buffer was not reset");

        reload(Map.of("analyze.models.pro.inherit-buffer", "ultra",
                "analyze.models.pro.actions.punish", false));
        player = player(UUID.randomUUID());
        commands.clear();
        alerts.clear();
        for (int i = 0; i < 6; i++) answer(player, ModelId.PRO);
        settle();
        require(commands.isEmpty() && alerts.size() == 5, "alert-only source punished or failed to alert");
        answer(player, ModelId.ULTRA);
        settle();
        require(alerts.get(5).startsWith("Ultra|100|1|"), "nonpunishing source increased group VL");

        reload(Map.of("analyze.models.pro.actions.alert", false,
                "analyze.models.pro.inherit-buffer", "ultra"));
        player = player(UUID.randomUUID());
        commands.clear();
        alerts.clear();
        for (int i = 0; i < 6; i++) answer(player, ModelId.PRO);
        settle();
        require(commands.size() == 1 && alerts.isEmpty(), "disabled alert prevented punishment or leaked");
    }

    private static void verifySourceSnapshot() throws Exception {
        reload(Map.of("analyze.models.pro.inherit-buffer", "ultra"));
        var player = player(UUID.randomUUID());
        commands.clear();
        alerts.clear();
        player.getCheckManager().getAimAI(ModelId.PRO).handleAnalyzeResult(1);
        player.getCheckManager().getAimAI(ModelId.ULTRA).handleAnalyzeResult(.9);
        player.getCheckManager().getAimAI(ModelId.PRO).handleAnalyzeResult(.8);
        player.getCheckManager().getAimAI(ModelId.ULTRA).handleAnalyzeResult(0);
        settle();
        require(alerts.equals(List.of("Ultra|90|1|90.00", "Pro|80|2|55.00")),
                "later model result changed queued alert attribution: " + alerts);
        close(player.getCheckManager().getAimAI(ModelId.PRO).getBuffer(), 24.75,
                "inherited snapshot did not reflect legitimate response");
    }

    private static void verifyEnableGates() throws Exception {
        for (var values : List.of(
                Map.<String, Object>of("analyze.enabled", false),
                Map.<String, Object>of("analyze.models.pro.enabled", false,
                        "analyze.models.ultra.enabled", false))) {
            reload(values);
            var player = player(UUID.randomUUID());
            commands.clear();
            alerts.clear();
            for (int i = 0; i < 6; i++) {
                answer(player, ModelId.PRO);
                answer(player, ModelId.ULTRA);
            }
            settle();
            require(commands.isEmpty() && alerts.isEmpty(), "disabled analysis emitted actions");
            close(player.getCheckManager().getAimAI(ModelId.PRO).getBuffer(), 0, "disabled Pro changed buffer");
            close(player.getCheckManager().getAimAI(ModelId.ULTRA).getBuffer(), 0, "disabled Ultra changed buffer");
        }
        reload(Map.of("analyze.models.ultra.enabled", false));
        var player = player(UUID.randomUUID());
        answer(player, ModelId.ULTRA);
        close(player.getCheckManager().getAimAI(ModelId.ULTRA).getBuffer(), 0, "disabled Ultra processed result");
        answer(player, ModelId.PRO);
        close(player.getCheckManager().getAimAI(ModelId.PRO).getBuffer(), 50, "enabled Pro stopped");
        reload(Map.of("analyze.enabled", false));
        answer(player, ModelId.PRO);
        close(player.getCheckManager().getAimAI(ModelId.PRO).getBuffer(), 50, "old configuration processed result");
        player.reload();
        close(player.getCheckManager().getAimAI(ModelId.PRO).getBuffer(), 0, "disable reload retained buffer");
    }

    private static void verifyMitigationEvent() throws Exception {
        reload(Map.of("analyze.mitigation.enabled", true,
                "analyze.mitigation.min-answers", 1,
                "analyze.mitigation.activation-score", 0,
                "analyze.mitigation.full-effect-score", .1,
                "analyze.models.pro.actions.punish", false));
        var attacker = player(UUID.randomUUID());
        var victim = player(UUID.randomUUID());
        answer(attacker, ModelId.PRO);
        var listener = new MitigationListener(plugin);
        var hit = hit(attacker, victim);
        listener.onDamage(hit);
        close(hit.getDamage(), 5, "melee multiplier not applied to damage event");
        hit = hit(attacker, victim);
        hit.setCancelled(true);
        listener.onDamage(hit);
        close(hit.getDamage(), 10, "cancelled damage was changed");
        owned = false;
        hit = hit(attacker, victim);
        listener.onDamage(hit);
        close(hit.getDamage(), 10, "off-owner event accessed mitigation");
        owned = true;
        reload(Map.of());
        hit = hit(attacker, victim);
        listener.onDamage(hit);
        close(hit.getDamage(), 10, "disabled mitigation changed damage");
    }

    private static void verifyReloadCancellation() throws Exception {
        var player = player(UUID.randomUUID());
        commands.clear();
        alerts.clear();
        for (int i = 0; i < 6; i++) answer(player, ModelId.PRO);
        barrier();
        reload(Map.of("analyze.models.pro.actions.punish", false));
        drain();
        require(commands.isEmpty() && alerts.isEmpty(), "queued old-policy punishment survived reload");
    }

    private static void verifyCombatBoundary() throws Exception {
        reload(Map.of());
        var player = player(UUID.randomUUID());
        var buffer = new ru.raveon.utils.math.RotationRingBuffer<ru.raveon.api.models.RotationFrame>(50);
        set(player, "rotationBuffer", buffer);
        for (int i = 0; i < 40; i++)
            buffer.addFrame(new ru.raveon.api.models.RotationFrame(1, 1, 1, 1, 1, 1, 1, 1));
        set(player, "lastCombatTime", System.currentTimeMillis() - 60_000);
        set(player, "isInCombat", true);
        long generation = player.getCheckManager().getGeneration(ModelId.PRO);
        player.markAttack(UUID.randomUUID());
        require(buffer.size() == 0 && player.isInCombat(), "New combat retained previous frames");
        require(player.getCheckManager().getGeneration(ModelId.PRO) > generation,
                "New combat accepted previous inference generation");
        var aim = player.getCheckManager().getAimAI();
        aim.handleRotation(player.getBukkitPlayer(), 150, 30);
        require(buffer.size() == 0, "First rotation after a gap manufactured an angle delta");
        aim.handleRotation(player.getBukkitPlayer(), 151, 31);
        require(buffer.size() == 1, "Fresh combat did not resume frame collection");
        close(aim.getLastDeltaYaw(), 1, "Fresh rotation used a stale yaw baseline");
        close(aim.getLastDeltaPitch(), 1, "Fresh rotation used a stale pitch baseline");
        generation = player.getCheckManager().getGeneration(ModelId.PRO);
        player.markAttack(UUID.randomUUID());
        require(buffer.size() == 1 && player.getCheckManager().getGeneration(ModelId.PRO) == generation,
                "Continuous attacks reset the analysis window");
        set(player, "lastCombatTime", System.currentTimeMillis() - 1);
        require(!player.isInCombat() && buffer.size() == 0, "Observed combat expiry did not reset the window");
    }

    @SuppressWarnings("unchecked")
    private static void verifyConnectionOwnership() throws Exception {
        reload(Map.of());
        UUID id = UUID.randomUUID();
        var old = player(id);
        var oldUser = allocate(com.github.retrooper.packetevents.protocol.player.User.class);
        set(oldUser, "profile", new com.github.retrooper.packetevents.protocol.player.UserProfile(id, "Player"));
        set(old, "user", oldUser);
        var current = player(id);
        var currentUser = allocate(com.github.retrooper.packetevents.protocol.player.User.class);
        set(currentUser, "profile", new com.github.retrooper.packetevents.protocol.player.UserProfile(id, "Player"));
        set(current, "user", currentUser);
        var manager = plugin.getPlayerDataManager();
        var connections = (Map<UUID, com.github.retrooper.packetevents.protocol.player.User>)
                field(PlayerDataManager.class, "connections").get(manager);
        connections.put(id, currentUser);
        plugin.getPlayerOnlineService().heartbeat(id, "Player", "fixture");
        plugin.getAlertManager().setAlertsEnabled(id, true, true);
        plugin.getViolationManager().addAiProbability(id, .7, false, ModelId.ULTRA);
        var training = current.getTrainData(ModelId.ULTRA);
        training.setDatasetsCollecting(true);
        long generation = current.getCheckManager().getGeneration(ModelId.PRO);
        require(manager.getPlayer(oldUser) == null && manager.remove(oldUser) == null,
                "Old User resolved or removed the replacement session");
        manager.onDisconnect(oldUser);
        require(manager.getPlayer(currentUser) == current, "Old disconnect removed the replacement session");
        require(current.getCheckManager().getGeneration(ModelId.PRO) == generation,
                "Old disconnect closed the new check manager");
        require(training.isDatasetsCollecting(), "Old disconnect stopped the new dataset session");
        require(plugin.getAlertManager().hasAlertsEnabled(id), "Old disconnect cleared new alerts");
        require(plugin.getViolationManager().getLocalProbabilities(id, ModelId.ULTRA).size() == 1,
                "Old disconnect cleared new probabilities");
        require(plugin.getPlayerOnlineService().getOnlinePlayerUuids().contains(id.toString()),
                "Old disconnect removed new presence");
        manager.onDisconnect(currentUser);
        require(manager.getPlayer(id) == null && !connections.containsKey(id), "Current disconnect leaked its session");
        require(!training.isDatasetsCollecting() && !plugin.getAlertManager().hasAlertsEnabled(id),
                "Current disconnect did not clean its data");
        require(plugin.getViolationManager().getLocalProbabilities(id, ModelId.ULTRA).isEmpty(),
                "Current disconnect retained local probabilities");
        require(!plugin.getPlayerOnlineService().getOnlinePlayerUuids().contains(id.toString()),
                "Current disconnect retained online presence");
        manager.onDisconnect(currentUser);
        connections.put(id, currentUser);
        manager.getExemptUsers().add(currentUser);
        manager.onDisconnect(oldUser);
        require(connections.get(id) == currentUser && manager.getExemptUsers().contains(currentUser),
                "Old disconnect removed an exempt replacement");
        manager.onDisconnect(currentUser);
        require(!connections.containsKey(id) && !manager.getExemptUsers().contains(currentUser),
                "Exempt session disconnect leaked state");
    }

    private static void verifyModelHistory() throws Exception {
        reload(Map.of("analyze.models.pro.enabled", false));
        var player = player(UUID.randomUUID());
        player.getCheckManager().getAimAI(ModelId.ULTRA).handleAnalyzeResult(.9);
        settle();
        var storage = plugin.getViolationManager().getProbabilityStorage();
        var pro = storage.getPlayerHistory(player.getUuid(), 0, 10, ModelId.PRO).get(5, TimeUnit.SECONDS);
        var ultra = storage.getPlayerHistory(player.getUuid(), 0, 10, ModelId.ULTRA).get(5, TimeUnit.SECONDS);
        require(pro.totalSnapshots() == 0 && ultra.data().getProbabilities().equals(List.of(.9)),
                "Ultra-only verdict was not stored in its own history");
        require(ru.raveon.menu.players.PlayersMenuPlaceholders.defaultModel() == ModelId.ULTRA,
                "Ultra-only server defaulted to empty Pro history");
        reload(Map.of());
        player.reload();
        player.getCheckManager().getAimAI(ModelId.PRO).handleAnalyzeResult(.1);
        player.getCheckManager().getAimAI(ModelId.ULTRA).handleAnalyzeResult(.8);
        settle();
        pro = storage.getPlayerHistory(player.getUuid(), 0, 10, ModelId.PRO).get(5, TimeUnit.SECONDS);
        ultra = storage.getPlayerHistory(player.getUuid(), 0, 10, ModelId.ULTRA).get(5, TimeUnit.SECONDS);
        require(pro.data().getProbabilities().equals(List.of(.1))
                        && ultra.data().getProbabilities().equals(List.of(.9, .8)),
                "Concurrent model histories were mixed");
        require(pro.data().getModel() == ModelId.PRO && ultra.data().getModel() == ModelId.ULTRA,
                "History lost its model identity");
        var menu = new ru.raveon.menu.history.HistoryMenu(ultra.data());
        require(menu.getMenuPlaceholders().get("model").equals("Ultra"),
                "History did not display the selected model");
        var summaries = storage.getPlayersData(List.of(player.getUuid()), 10, ModelId.ULTRA)
                .get(5, TimeUnit.SECONDS);
        var placeholders = ru.raveon.menu.players.PlayersMenuPlaceholders.playerPlaceholders(
                summaries.get(0), plugin.getMainConfigManager().getMenuSettings(), 0, 1, 1);
        require(placeholders.get("model").equals("Ultra"), "Ranking lost the model label");
        close(summaries.get(0).getAverageProbability(), .85, "Ranking mixed model probabilities");
    }

    private static void verifyDatabaseFailure() throws Exception {
        reload(Map.of());
        var player = player(UUID.randomUUID());
        commands.clear();
        alerts.clear();
        barrier();
        plugin.getMainConfigManager().getDatabaseManager().shutdown().get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 6; i++) answer(player, ModelId.PRO);
        drain();
        require(commands.isEmpty(), "failed storage produced a punishment");
    }

    private static EntityDamageByEntityEvent hit(RaveonPlayer attacker, RaveonPlayer victim) {
        return new EntityDamageByEntityEvent(attacker.getBukkitPlayer(), victim.getBukkitPlayer(),
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10);
    }

    private static void answer(RaveonPlayer player, ModelId model) {
        player.getCheckManager().getAimAI(model).handleAnalyzeResult(1);
    }

    private static void reload(Map<String, Object> values) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(checks);
        values.forEach(yaml::set);
        yaml.save(folder.resolve("checks.yml").toFile());
        set(plugin, "configuration", RuntimeConfiguration.load(configurationPlugin, plugin.getConfiguration()));
    }

    private static RaveonPlayer player(UUID uuid) throws Exception {
        Player entity = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> "Player";
            case "isOnline", "isValid" -> true;
            default -> defaultValue(m.getReturnType());
        });
        RaveonPlayer player = allocate(RaveonPlayer.class);
        set(player, "uuid", uuid);
        set(player, "name", "Player");
        set(player, "bukkitPlayer", entity);
        set(player, "ultraData", new UltraData());
        set(player, "checkManager", new CheckManager(player));
        @SuppressWarnings("unchecked")
        Map<UUID, RaveonPlayer> players = (Map<UUID, RaveonPlayer>)
                field(PlayerDataManager.class, "playerDataMap").get(plugin.getPlayerDataManager());
        players.put(uuid, player);
        plugin.getViolationManager().getProbabilityStorage().ensurePlayer(uuid, "Player").get(5, TimeUnit.SECONDS);
        return player;
    }

    private static void barrier() throws Exception {
        plugin.getMainConfigManager().getDatabaseManager().submit(() -> true).get(5, TimeUnit.SECONDS);
    }

    private static void settle() throws Exception {
        barrier();
        drain();
        barrier();
    }

    private static void drain() {
        Runnable task;
        while ((task = scheduled.poll()) != null) task.run();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == char.class) return '\0';
        return 0;
    }
}
