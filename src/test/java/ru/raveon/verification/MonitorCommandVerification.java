package ru.raveon.verification;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import ru.raveon.Raveon;
import ru.raveon.command.commands.raveon.subcommands.MonitorSubCommand;
import ru.raveon.config.MainConfigManager;
import ru.raveon.manager.analytic.MonitorManager;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;

import java.lang.reflect.*;
import java.util.*;

public final class MonitorCommandVerification {
    private static int checks;
    private static final Map<UUID, List<String>> chats = new HashMap<>(), bars = new HashMap<>();
    private static final Map<UUID, Boolean> online = new HashMap<>();
    private static MonitorManager manager;
    private static MonitorSubCommand command;
    private static final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();

    private static void eq(Object actual, Object expected, String reason) {
        checks++;
        if (!Objects.equals(actual, expected))
            throw new AssertionError(reason + ": " + actual + " != " + expected);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        field(object.getClass(), name).set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == void.class) return null;
        return 0;
    }

    private static Player viewer(String name) {
        UUID id = UUID.randomUUID();
        chats.put(id, new ArrayList<>());
        bars.put(id, new ArrayList<>());
        online.put(id, true);
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, m, args) ->
                                switch (m.getName()) {
                                    case "getUniqueId" -> id;
                                    case "getName" -> name;
                                    case "isOnline", "isValid" -> online.get(id);
                                    case "equals" -> proxy == args[0];
                                    case "hashCode" -> id.hashCode();
                                    case "toString" -> name;
                                    case "hasPermission" -> true;
                                    case "sendMessage" -> {
                                        if (args[0] instanceof String text) chats.get(id).add(text);
                                        else if (args[0] instanceof String[] texts)
                                            chats.get(id).addAll(Arrays.asList(texts));
                                        yield null;
                                    }
                                    case "sendActionBar" -> {
                                        bars.get(id).add((String) args[0]);
                                        yield null;
                                    }
                                    default -> defaultValue(m.getReturnType());
                                });
    }

    private static List<String> chat(Player player) {
        return chats.get(player.getUniqueId());
    }

    private static List<String> bar(Player player) {
        return bars.get(player.getUniqueId());
    }

    private static void clear(Player... players) {
        for (Player p : players) {
            chat(p).clear();
            bar(p).clear();
        }
    }

    private static void issue(Player viewer, String... args) {
        command.handle(viewer, args);
        drain();
    }

    private static void publish(Player target, double probability, double buffer, ModelId model) {
        manager.publish(target, probability, buffer, model);
        drain();
    }

    private static void drain() {
        int iterations = 0;
        while (!scheduled.isEmpty()) {
            if (++iterations > 1000) throw new AssertionError("Unbounded monitor task queue");
            scheduled.remove().run();
        }
    }

    private static void tick() throws Exception {
        Method m = MonitorManager.class.getDeclaredMethod("tick");
        m.setAccessible(true);
        m.invoke(manager);
        drain();
    }

    private static Object subscription(Player viewer, String component) throws Exception {
        Map<?, ?> subs = (Map<?, ?>) field(MonitorManager.class, "chatSubscriptions").get(manager);
        Object sub = subs.get(viewer.getUniqueId());
        if (sub == null) return null;
        Method m = sub.getClass().getDeclaredMethod(component);
        m.setAccessible(true);
        return m.invoke(sub);
    }

    public static void main(String[] args) throws Exception {
        checks = 0;
        scheduled.clear();
        var task = HologramPacketVerification.proxy(org.bukkit.scheduler.BukkitTask.class,
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
        var scheduler = HologramPacketVerification.proxy(org.bukkit.scheduler.BukkitScheduler.class,
                (proxy, method, arguments) -> {
                    if (method.getName().equals("runTask")) {
                        scheduled.add((Runnable) arguments[1]);
                        return task;
                    }
                    if (method.getName().startsWith("runTaskTimer")) return task;
                    return defaultValue(method.getReturnType());
                });
        var logger = java.util.logging.Logger.getLogger("MonitorCommandVerification");
        Server server = HologramPacketVerification.proxy(Server.class,
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    case "getLogger" -> logger;
                    case "isPrimaryThread" -> true;
                    case "getName", "getVersion" -> "test";
                    case "getBukkitVersion" -> "1.20.1-R0.1";
                    default -> defaultValue(method.getReturnType());
                });
        field(Bukkit.class, "server").set(null, server);
        Raveon plugin = allocate(Raveon.class);
        set(plugin, "isEnabled", true);
        set(plugin, "logger", logger);
        Raveon.INSTANCE = plugin;
        MainConfigManager config = allocate(MainConfigManager.class);
        set(config, "aiChanceColors", new TreeMap<Double, String>());
        set(plugin, "configuration", new ru.raveon.config.RuntimeConfiguration(
                config, null, null, null, null));
        var players = new ru.raveon.manager.anticheat.PlayerDataManager();
        set(plugin, "playerDataManager", players);
        var violations = allocate(ru.raveon.manager.alert.ViolationManager.class);
        Map<ModelId, Map<UUID, Deque<Double>>> probabilities = new EnumMap<>(ModelId.class);
        for (ModelId model : ModelId.values()) {
            probabilities.put(model, new java.util.concurrent.ConcurrentHashMap<>());
        }
        set(violations, "probabilitiesByModel", probabilities);
        set(plugin, "violationManager", violations);
        Player target = viewer("Player");
        RaveonPlayer playerData = allocate(RaveonPlayer.class);
        set(playerData, "uuid", target.getUniqueId());
        set(playerData, "name", target.getName());
        set(playerData, "bukkitPlayer", target);
        ((Map<UUID, RaveonPlayer>) field(players.getClass(), "playerDataMap").get(players))
                .put(target.getUniqueId(), playerData);
        verify(plugin, config, target);
    }

    public static void verify(Raveon plugin, MainConfigManager config, Player target)
            throws Exception {
        Object previousServer = field(Bukkit.class, "server").get(null);
        MonitorManager previousManager = plugin.getMonitorManager();
        var previousLanguage = config.getLanguageService();
        Map<String, String> messages =
                Map.ofEntries(
                        Map.entry("monitor.action_bar.format", "{model}|{player}|{prob}|{buffer}"),
                        Map.entry("monitor.action_bar.waiting", "WAIT|{player}"),
                        Map.entry("monitor.chat.format", "{model}|{player}|{prob}|{buffer}"),
                        Map.entry("monitor.chat.all_name", "ALL_PLAYERS"),
                        Map.entry("monitor.action_bar.trend.up", "+{value}"),
                        Map.entry("monitor.action_bar.trend.down", "{value}"),
                        Map.entry("monitor.action_bar.trend.equal", "{value}"),
                        Map.entry("monitor.usage", "USAGE"),
                        Map.entry("monitor.only_player", "ONLY_PLAYER"),
                        Map.entry("monitor.enabled", "ENABLED|{player}|{model}"),
                        Map.entry("monitor.switched", "SWITCHED|{player}|{model}"),
                        Map.entry("monitor.disabled", "DISABLED"),
                        Map.entry("monitor.not_running", "NOT_RUNNING"),
                        Map.entry("monitor.player_not_found", "NOT_FOUND"),
                        Map.entry("monitor.target_left", "LEFT|{player}"),
                        Map.entry("monitor.chat.enabled", "CHAT_ENABLED|{player}|{model}"),
                        Map.entry("monitor.chat.switched", "CHAT_SWITCHED|{player}|{model}"),
                        Map.entry("monitor.chat.disabled", "CHAT_DISABLED"));
        LanguageTestSupport.install(config, messages);
        Player a = viewer("ViewerA"),
                b = viewer("ViewerB"),
                other = viewer("Other"),
                reserved = viewer("pro");
        List<Player> players = List.of(a, b, target, other, reserved);
        Server server =
                (Server)
                        Proxy.newProxyInstance(
                                Server.class.getClassLoader(),
                                new Class<?>[] {Server.class},
                                (proxy, m, args) -> {
                                    if (m.getName().equals("isPrimaryThread")) return true;
                                    if (m.getName().equals("getOnlinePlayers"))
                                        return players.stream().filter(Player::isOnline).toList();
                                    if (m.getName().equals("getPlayer")
                                            || m.getName().equals("getPlayerExact")) {
                                        Object wanted = args[0];
                                        return players.stream()
                                                .filter(
                                                        p ->
                                                                p.isOnline()
                                                                        && (wanted instanceof UUID
                                                                                ? p.getUniqueId()
                                                                                        .equals(
                                                                                                wanted)
                                                                                : p.getName()
                                                                                        .equalsIgnoreCase(
                                                                                                String
                                                                                                        .valueOf(
                                                                                                                wanted))))
                                                .findFirst()
                                                .orElse(null);
                                    }
                                    return m.invoke(previousServer, args);
                                });
        Map<UUID, RaveonPlayer> data =
                (Map<UUID, RaveonPlayer>)
                        field(plugin.getPlayerDataManager().getClass(), "playerDataMap")
                                .get(plugin.getPlayerDataManager());
        try {
            field(Bukkit.class, "server").set(null, server);
            RaveonPlayer otherData = allocate(RaveonPlayer.class);
            set(otherData, "uuid", other.getUniqueId());
            set(otherData, "name", other.getName());
            set(otherData, "bukkitPlayer", other);
            data.put(other.getUniqueId(), otherData);
            manager = new MonitorManager(plugin);
            set(plugin, "monitorManager", manager);
            command = new MonitorSubCommand();

            issue(a, "monitor", "chat");
            eq(subscription(a, "targetId"), null, "chat without nick watches everyone");
            eq(subscription(a, "modelFilter").toString(), "ALL", "default all filter");
            clear(a);
            publish(target, .8, 3, ModelId.PRO);
            publish(other, .2, 4, ModelId.ULTRA);
            eq(chat(a).size(), 2, "all players and both models");
            eq(chat(a).get(0).startsWith("Pro|Player|"), true, "all chat Pro");
            eq(chat(a).get(1).startsWith("Ultra|Other|"), true, "all chat other Ultra");

            issue(a, "monitor", "chat", "ULTRA");
            eq(subscription(a, "targetId"), null, "single model token is not a nick");
            eq(subscription(a, "modelFilter").toString(), "ULTRA", "case insensitive model");
            clear(a);
            publish(target, .8, 3, ModelId.PRO);
            publish(target, .2, 4, ModelId.ULTRA);
            publish(other, .2, 4, ModelId.ULTRA);
            eq(chat(a).size(), 2, "global Ultra filter");
            eq(chat(a).stream().allMatch(x -> x.startsWith("Ultra|")), true, "Pro excluded");

            issue(a, "monitor", "chat", "Player", "pro");
            clear(a);
            publish(other, .8, 3, ModelId.PRO);
            publish(target, .2, 4, ModelId.ULTRA);
            publish(target, .8, 3, ModelId.PRO);
            eq(chat(a).size(), 1, "nick AND model filters");
            eq(chat(a).get(0).startsWith("Pro|Player|"), true, "targeted Pro only");
            issue(a, "monitor", "chat", "Player");
            eq(subscription(a, "modelFilter").toString(), "ALL", "target chat defaults all");
            clear(a);
            publish(target, .8, 3, ModelId.PRO);
            publish(target, .2, 4, ModelId.ULTRA);
            eq(chat(a).size(), 2, "target receives both models");
            issue(a, "monitor", "chat", "Player");
            eq(subscription(a, "modelFilter"), null, "identical chat toggles off");

            issue(a, "monitor", "chat", "pro", "all");
            eq(
                    subscription(a, "targetId"),
                    reserved.getUniqueId(),
                    "explicit model disambiguates player named pro");
            issue(a, "monitor", "chat", "pro");
            eq(subscription(a, "targetId"), null, "chat pro means global Pro filter");
            issue(b, "monitor", "chat", "Player", "ultra");
            issue(a, "monitor", "stop");
            clear(a, b);
            publish(target, .2, 4, ModelId.ULTRA);
            eq(chat(a).size(), 0, "stop only current viewer");
            eq(chat(b).size(), 1, "other chat viewer survives stop");
            issue(b, "monitor", "chat", "Player", "invalid");
            eq(
                    subscription(b, "modelFilter").toString(),
                    "ULTRA",
                    "invalid model preserves subscription");
            issue(b, "monitor", "stop");

            for (String[] args :
                    List.of(
                            new String[] {"monitor"},
                            new String[] {"monitor", "prob"},
                            new String[] {"monitor", "prob", "Player", "bad"},
                            new String[] {"monitor", "prob", "Player", "pro", "extra"},
                            new String[] {"monitor", "chat", "Player", "bad"},
                            new String[] {"monitor", "stop", "extra"})) {
                clear(a);
                issue(a, args);
                eq(chat(a).get(0), "USAGE", "invalid syntax rejected");
                eq(manager.isMonitoring(a), false, "invalid syntax creates no session");
            }
            clear(a);
            issue(a, "monitor", "chat", "Missing");
            eq(chat(a).get(0), "NOT_FOUND", "unknown chat nick");
            clear(a);
            issue(a, "monitor", "prob", "Missing");
            eq(chat(a).get(0), "NOT_FOUND", "unknown prob nick");

            issue(a, "monitor", "prob", "Player", "pro");
            issue(b, "monitor", "prob", "Player", "ultra");
            clear(a, b);
            publish(target, .8, 3, ModelId.PRO);
            publish(target, .2, 4, ModelId.ULTRA);
            tick();
            eq(bar(a), List.of("Pro|Player|80|3.00"), "Pro actionbar ignores newer Ultra snapshot");
            eq(bar(b), List.of("Ultra|Player|20|4.00"), "Ultra actionbar own snapshot");
            publish(target, .4, 5, ModelId.ULTRA);
            tick();
            eq(bar(a).size(), 1, "Ultra cannot overwrite/resend Pro actionbar");
            eq(bar(b).get(1), "Ultra|Player|40|5.00", "Ultra actionbar updates");
            issue(a, "monitor", "prob", "Player", "ultra");
            eq(
                    chat(a).get(chat(a).size() - 1),
                    "SWITCHED|Player|Ultra",
                    "same nick new filter switches, not disables");
            tick();
            eq(
                    bar(a).get(bar(a).size() - 1),
                    "Ultra|Player|40|5.00",
                    "switched actionbar uses selected model cache");
            issue(a, "monitor", "prob", "Player", "ultra");
            eq(manager.isMonitoring(a), false, "exact prob toggle off");
            eq(manager.isMonitoring(b), true, "other actionbar remains");
            issue(b, "monitor", "prob", "Player");
            clear(b);
            publish(target, .7, 6, ModelId.PRO);
            tick();
            publish(target, .3, 7, ModelId.ULTRA);
            tick();
            eq(
                    bar(b),
                    List.of("Pro|Player|70|6.00", "Ultra|Player|30|7.00"),
                    "prob default all follows both models");

            LanguageTestSupport.update(config, "monitor.action_bar.format", "{prob}%|{buffer}");
            clear(b);
            publish(target, .66, 8, ModelId.PRO);
            tick();
            publish(target, .22, 9, ModelId.ULTRA);
            tick();
            eq(
                    org.bukkit.ChatColor.stripColor(bar(b).get(0)),
                    "[Pro] 66%|8.00",
                    "old format automatically shows Pro on left");
            eq(
                    org.bukkit.ChatColor.stripColor(bar(b).get(1)),
                    "[Ultra] 22%|9.00",
                    "latest Ultra response changes label and probability together");
            LanguageTestSupport.update(config, "monitor.action_bar.format", "[{model}] {prob}%|{buffer}");
            publish(target, .55, 10, ModelId.PRO);
            tick();
            eq(
                    bar(b).get(bar(b).size() - 1),
                    "[Pro] 55%|10.00",
                    "existing bracket model placeholder has no duplicate label");
            LanguageTestSupport.update(config, "monitor.action_bar.format", "({model}) {prob}%|{buffer}");
            publish(target, .11, 11, ModelId.ULTRA);
            tick();
            eq(
                    bar(b).get(bar(b).size() - 1),
                    "(Ultra) 11%|11.00",
                    "existing brace placeholder has no duplicate label");
            LanguageTestSupport.update(config, "monitor.action_bar.format", "{prob}%|{buffer}");
            issue(b, "monitor", "prob", "Player", "pro");
            clear(b);
            publish(target, .44, 12, ModelId.PRO);
            publish(target, .77, 13, ModelId.ULTRA);
            tick();
            eq(
                    org.bukkit.ChatColor.stripColor(bar(b).get(0)),
                    "[Pro] 44%|12.00",
                    "old format label follows selected model, not newer excluded model");
            LanguageTestSupport.update(config, "monitor.action_bar.format", "{model}|{player}|{prob}|{buffer}");
            manager.shutdown();
            issue(a, "monitor", "prob", "Player", "ultra");
            clear(a);
            publish(target, .8, 3, ModelId.PRO);
            tick();
            eq(bar(a), List.of("WAIT|Player"), "selected model waits, never shows another model");
            publish(target, .2, 4, ModelId.ULTRA);
            tick();
            eq(bar(a).get(1), "Ultra|Player|20|4.00", "waiting ends on selected model response");
            issue(a, "monitor", "chat");
            issue(a, "monitor", "stop");
            clear(a);
            publish(target, .3, 4, ModelId.ULTRA);
            tick();
            eq(chat(a).size(), 0, "stop clears chat and prob");
            eq(bar(a).size(), 0, "stop clears actionbar");
            eq(
                    ((Map<?, ?>) field(MonitorManager.class, "modelSnapshots").get(manager))
                            .isEmpty(),
                    true,
                    "unused per-model snapshots cleaned");

            issue(a, "monitor", "prob", "Other", "pro");
            issue(b, "monitor", "prob", "Player", "pro");
            publish(other, .2, 4, ModelId.PRO);
            publish(target, .8, 3, ModelId.PRO);
            clear(a, b);
            online.put(other.getUniqueId(), false);
            tick();
            eq(manager.isMonitoring(a), false, "left target stops its viewer");
            eq(chat(a), List.of("LEFT|Other"), "left target notice");
            eq(manager.isMonitoring(b), true, "other target monitor survives disconnect");
            issue(b, "monitor", "stop");

            eq(
                    command.tabComplete(a, new String[] {"monitor", ""}),
                    List.of("chat", "prob", "stop"),
                    "mode completion");
            eq(
                    command.tabComplete(a, new String[] {"monitor", "chat", "u"}),
                    List.of("ultra"),
                    "chat model completion without nick");
            eq(
                    command.tabComplete(a, new String[] {"monitor", "chat", "Player", ""}),
                    List.of("pro", "ultra", "all"),
                    "chat trailing model completion");
            eq(
                    command.tabComplete(a, new String[] {"monitor", "prob", "Player", "u"}),
                    List.of("ultra"),
                    "prob model completion");
            eq(
                    command.tabComplete(a, new String[] {"monitor", "prob", ""}).contains("all"),
                    false,
                    "prob nick position does not suggest all model");
            eq(
                    command.tabComplete(a, new String[] {"monitor", "chat", "Player", "pro", ""}),
                    List.of(),
                    "no extra completion");
            String customUsage = "Custom help: /raveon monitor chat [name]";
            LanguageTestSupport.update(config, "monitor.usage", customUsage);
            clear(a);
            issue(a, "monitor", "prob");
            eq(chat(a).get(0), customUsage, "custom help is not migrated");
            LanguageTestSupport.update(config, "monitor.usage", "");
            clear(a);
            issue(a, "monitor", "prob");
            eq(chat(a).get(0), "", "explicitly blank custom help is preserved");
            List<String> consoleMessages = new ArrayList<>();
            CommandSender console =
                    (CommandSender)
                            Proxy.newProxyInstance(
                                    CommandSender.class.getClassLoader(),
                                    new Class<?>[] {CommandSender.class},
                                    (proxy, m, args) -> {
                                        if (m.getName().equals("sendMessage"))
                                            consoleMessages.add((String) args[0]);
                                        return defaultValue(m.getReturnType());
                                    });
            command.handle(console, new String[] {"monitor", "chat"});
            eq(consoleMessages, List.of("ONLY_PLAYER"), "console rejected");
            System.out.println(
                    "MONITOR_SELECTION PASS: assertions="
                            + checks
                            + "; optional chat nick/all players; required prob nick; Pro/Ultra/All;"
                            + " viewer isolation; selected actionbar cache; stop; completion;"
                            + " invalid args; offline cleanup");
        } finally {
            if (manager != null) manager.shutdown();
            data.remove(other.getUniqueId());
            set(plugin, "monitorManager", previousManager);
            field(Bukkit.class, "server").set(null, previousServer);
            set(config, "languageService", previousLanguage);
        }
    }
}
