package ru.raveon.verification;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.*;
import com.github.retrooper.packetevents.netty.*;
import com.github.retrooper.packetevents.netty.buffer.*;
import com.github.retrooper.packetevents.netty.channel.*;
import com.github.retrooper.packetevents.protocol.*;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.*;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.*;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.netty.buffer.*;
import io.netty.buffer.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import ru.raveon.manager.analytic.hologram.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;

public final class HologramPacketVerification {
    static final List<PacketWrapper<?>> packets = new ArrayList<>();
    static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    static ServerVersion version;
    static RecordingUser user;
    static Player viewer;
    static int assertions;
    static ClientScoreboard scoreboardClient;

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return 0;
    }

    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    static void drain() {
        int count = 0;
        while (!queue.isEmpty()) {
            if (++count > 100) throw new AssertionError("Unbounded queue");
            queue.remove().run();
        }
    }

    static final class RecordingUser extends User {
        RecordingUser(UUID id) {
            super(new Object(), ConnectionState.PLAY, version.toClientVersion(), new UserProfile(id, "viewer"));
        }
        @Override public void sendPacketSilently(PacketWrapper<?> packet) {
            packets.add(packet);
            if (scoreboardClient != null) scoreboardClient.receive(packet);
        }
    }

    static final class Event extends PacketSendEvent {
        Event(PacketWrapper<?> wrapper, boolean copied) {
            super(wrapper.getNativePacketId(), wrapper.getPacketTypeData().getPacketType(),
                    version, user.getChannel(), user, viewer, wrapper.buffer);
            if (copied) setLastUsedWrapper(wrapper);
        }
    }

    static final class Api extends PacketEventsAPI<Object> {
        final ChannelOperator channel = proxy(ChannelOperator.class, (p,m,a) -> {
            if (m.getName().equals("runInEventLoop")) { queue.add((Runnable) a[1]); return null; }
            if (m.getName().equals("isOpen")) return true;
            return defaultValue(m.getReturnType());
        });
        final PlayerManager players = proxy(PlayerManager.class, (p,m,a) -> {
            if (m.getName().equals("getUser")) return user;
            if (m.getName().equals("getClientVersion")) return user.getClientVersion();
            if (m.getName().equals("getChannel")) return user.getChannel();
            if (m.getName().equals("sendPacket") || m.getName().equals("sendPacketSilently")) {
                packets.add((PacketWrapper<?>) a[1]);
                return null;
            }
            return defaultValue(m.getReturnType());
        });
        final NettyManager netty = new NettyManager() {
            public ChannelOperator getChannelOperator() { return channel; }
            public ByteBufOperator getByteBufOperator() { return new ByteBufOperatorModernImpl(); }
            public ByteBufAllocationOperator getByteBufAllocationOperator() { return new ByteBufAllocationOperatorModernImpl(); }
        };
        public void load() {}
        public boolean isLoaded() { return true; }
        public void init() {}
        public boolean isInitialized() { return true; }
        public void terminate() {}
        public boolean isTerminated() { return false; }
        public Object getPlugin() { return null; }
        public ServerManager getServerManager() { return () -> version; }
        public ProtocolManager getProtocolManager() { return null; }
        public PlayerManager getPlayerManager() { return players; }
        public NettyManager getNettyManager() { return netty; }
        public ChannelInjector getInjector() { return null; }
        public Logger getLogger() { return Logger.getLogger("test"); }
    }

    static void observe(BelowNameBridge bridge, PacketWrapper<?> wrapper) {
        Event event = new Event(wrapper, true);
        bridge.observe(event);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    static WrapperPlayServerScoreboardObjective objective(String name, WrapperPlayServerScoreboardObjective.ObjectiveMode mode) {
        return new WrapperPlayServerScoreboardObjective(name, mode, Component.empty(),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER, ScoreFormat.blankScore());
    }

    static long objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode mode) {
        return packets.stream().filter(p -> p instanceof WrapperPlayServerScoreboardObjective o && o.getMode() == mode).count();
    }

    static void scoreboardTests() {
        BelowNameBridge bridge = new BelowNameBridge();
        boolean supported = version.isNewerThanOrEquals(ServerVersion.V_1_20_3);
        check(bridge.supportsBelowName(viewer) == supported, "Protocol version gate");
        if (!supported) return;
        check(bridge.canUse(viewer), "Empty below-name slot");
        for (int i = 0; i < 10000; i++) bridge.update(viewer, Map.of("target", "§a" + i));
        check(queue.size() == 1, "Coalesced scoreboard queue");
        drain();
        check(objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE) == 1, "One CREATE");
        String ours = ((WrapperPlayServerScoreboardObjective) packets.get(0)).getName();
        check(ours.length() <= 16, "Objective name length");
        check(packets.size() == 3, "One CREATE, display and latest score");
        packets.clear();
        bridge.update(viewer, Map.of("target", "§a9999"));
        drain();
        check(packets.isEmpty(), "Unchanged score not sent");
        observe(bridge, objective("foreign", WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE));
        observe(bridge, new WrapperPlayServerDisplayScoreboard(2, "foreign"));
        drain();
        check(!bridge.canUse(viewer), "Yield to foreign display");
        check(objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) == 1, "Remove own objective once");
        check(((WrapperPlayServerScoreboardObjective) packets.get(0)).getName().equals(ours), "Foreign objective untouched");
        packets.clear();
        bridge.update(viewer, Map.of("target", "§c100"));
        bridge.clear(viewer);
        drain();
        check(packets.isEmpty(), "No update or foreign delete while yielded");
        observe(bridge, objective("foreign", WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE));
        drain();
        check(bridge.canUse(viewer), "Foreign removal releases slot");
        bridge.update(viewer, Map.of("target", "42"));
        drain();
        String current = packets.stream().filter(p -> p instanceof WrapperPlayServerScoreboardObjective)
                .map(p -> ((WrapperPlayServerScoreboardObjective)p).getName()).findFirst().orElseThrow();
        packets.clear();
        observe(bridge, objective(current, WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE));
        check(objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) == 1, "Collision removes ours before foreign create");
        drain();
        packets.clear();
        bridge.clear(viewer);
        drain();
        check(packets.isEmpty(), "Collision owner not removed");
        check(!bridge.canUse(viewer), "Collision yields slot");
        bridge.forget(viewer.getUniqueId());
        user = new RecordingUser(viewer.getUniqueId());
        check(bridge.canUse(viewer), "Reconnect has fresh state");
        bridge.update(viewer, Map.of("target", "new"));
        drain();
        String next = ((WrapperPlayServerScoreboardObjective) packets.get(0)).getName();
        check(!current.equals(next), "Unique objective on reconnect");
        packets.clear();
        observe(bridge, objective(next, WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE));
        bridge.clear(viewer);
        drain();
        check(packets.isEmpty(), "Never delete externally updated objective");
        bridge.shutdown();
        drain();
    }

    static final class ClientScoreboard {
        final Set<String> objectives = new HashSet<>();
        final List<PacketWrapper<?>> trace = new ArrayList<>();
        String belowName = "";

        void receive(PacketWrapper<?> packet) {
            trace.add(packet);
            if (packet instanceof WrapperPlayServerScoreboardObjective objective) {
                switch (objective.getMode()) {
                    case CREATE -> check(objectives.add(objective.getName()),
                            "Client receives no duplicate objective CREATE");
                    case UPDATE -> check(objectives.contains(objective.getName()),
                            "Client receives UPDATE only for an existing objective");
                    case REMOVE -> {
                        check(objectives.remove(objective.getName()),
                                "Client receives REMOVE only for an existing objective");
                        if (belowName.equals(objective.getName())) belowName = "";
                    }
                }
            } else if (packet instanceof WrapperPlayServerDisplayScoreboard display) {
                String name = display.getScoreName();
                check(name == null || name.isEmpty() || objectives.contains(name),
                        "Display references an existing objective");
                if (display.getPosition() == 2) belowName = name == null ? "" : name;
            } else if (packet instanceof WrapperPlayServerUpdateScore score) {
                check(objectives.contains(score.getObjectiveName()),
                        "Score references an existing objective");
            } else if (packet instanceof WrapperPlayServerResetScore reset) {
                check(reset.getObjective() == null || objectives.contains(reset.getObjective()),
                        "Reset references an existing objective");
            }
        }

        void verifyCodecs() {
            for (PacketWrapper<?> wrapper : trace) {
                ByteBuf buffer = Unpooled.buffer();
                try {
                    wrapper.buffer = buffer;
                    wrapper.setClientVersion(version.toClientVersion());
                    wrapper.write();
                    Event event = new Event(wrapper, false);
                    if (wrapper instanceof WrapperPlayServerScoreboardObjective objective) {
                        var decoded = new WrapperPlayServerScoreboardObjective(event);
                        check(decoded.getName().equals(objective.getName()) && decoded.getMode() == objective.getMode(),
                                "TAB objective wire roundtrip");
                    } else if (wrapper instanceof WrapperPlayServerDisplayScoreboard display) {
                        var decoded = new WrapperPlayServerDisplayScoreboard(event);
                        check(decoded.getPosition() == display.getPosition()
                                        && Objects.equals(decoded.getScoreName(), display.getScoreName()),
                                "TAB display wire roundtrip");
                    } else if (wrapper instanceof WrapperPlayServerUpdateScore score) {
                        var decoded = new WrapperPlayServerUpdateScore(event);
                        check(decoded.getObjectiveName().equals(score.getObjectiveName())
                                        && decoded.getEntityName().equals(score.getEntityName())
                                        && decoded.getValue().equals(score.getValue()),
                                "TAB score wire roundtrip");
                    } else if (wrapper instanceof WrapperPlayServerResetScore) {
                        new WrapperPlayServerResetScore(event);
                    } else {
                        throw new AssertionError("Unexpected scoreboard packet " + wrapper.getClass());
                    }
                    check(!buffer.isReadable(), "TAB packet payload consumed");
                } finally {
                    buffer.release();
                    wrapper.buffer = null;
                }
            }
        }
    }

    static WrapperPlayServerScoreboardObjective tabObjective(
            WrapperPlayServerScoreboardObjective.ObjectiveMode mode, String title) {
        return new WrapperPlayServerScoreboardObjective("TAB-BelowName", mode, Component.text(title, NamedTextColor.RED),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                version.isNewerThanOrEquals(ServerVersion.V_1_20_3)
                        ? ScoreFormat.fixedScore(Component.text("NPC")) : null);
    }

    static byte[] packetBytes(PacketWrapper<?> wrapper) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            wrapper.buffer = buffer;
            wrapper.setClientVersion(version.toClientVersion());
            wrapper.write();
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
            wrapper.buffer = null;
        }
    }

    static void tabPacket(BelowNameBridge bridge, PacketWrapper<?> wrapper) {
        Event event = new Event(wrapper, true);
        bridge.observe(event);
        check(!event.isCancelled(), "TAB packet is not cancelled");
        PacketWrapper<?> forwarded = event.getLastUsedWrapper();
        check(Arrays.equals(packetBytes(wrapper), packetBytes(forwarded)), "TAB packet payload is unchanged");
        scoreboardClient.receive(forwarded);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    static void tabScore(BelowNameBridge bridge, int health) {
        tabPacket(bridge, new WrapperPlayServerUpdateScore("target",
                WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, "TAB-BelowName", health, null,
                version.isNewerThanOrEquals(ServerVersion.V_1_20_3)
                        ? ScoreFormat.fixedScore(Component.text(Integer.toString(health), NamedTextColor.RED)) : null));
    }

    static void tabRegister(BelowNameBridge bridge) {
        tabPacket(bridge, tabObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, "Health"));
        tabPacket(bridge, new WrapperPlayServerDisplayScoreboard(2, "TAB-BelowName"));
        tabScore(bridge, 20);
    }

    static void tabTests() {
        for (boolean tabFirst : new boolean[]{true, false}) {
            drain();
            packets.clear();
            BelowNameBridge bridge = new BelowNameBridge();
            boolean supported = bridge.supportsBelowName(viewer);
            scoreboardClient = new ClientScoreboard();
            if (!tabFirst && supported) {
                check(bridge.canUse(viewer), "Raveon starts with an empty below-name slot");
                bridge.update(viewer, Map.of("target", "35%"));
                drain();
            }
            tabRegister(bridge);
            check(!bridge.canUse(viewer), "TAB owns the below-name slot in either activation order");
            bridge.update(viewer, supported ? Map.of("target", "36%") : Map.of());
            drain();
            check(scoreboardClient.belowName.equals("TAB-BelowName"), "TAB remains displayed after Raveon update");
            check(objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE)
                            == (!tabFirst && supported ? 1 : 0),
                    "TAB activation removes only an existing Raveon objective once");
            check(packets.stream().noneMatch(packet ->
                            packet instanceof WrapperPlayServerScoreboardObjective objective
                                    && objective.getName().equals("TAB-BelowName")),
                    "Raveon never mutates the TAB objective");
            packets.clear();
            for (int i = 0; i < 8; i++) {
                tabPacket(bridge, tabObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE,
                        "Health " + i));
                tabScore(bridge, 12 + i);
                bridge.update(viewer, supported ? Map.of("target", i + "%") : Map.of());
            }
            drain();
            check(packets.isEmpty(), "TAB title and score refresh cause no Raveon scoreboard traffic");
            check(!bridge.canUse(viewer), "TAB title and score refresh retain foreign ownership");
            bridge.clear(viewer);
            drain();
            check(packets.isEmpty(), "Raveon feature disable preserves TAB");
            bridge.update(viewer, supported ? Map.of("target", "48%") : Map.of());
            drain();
            check(packets.isEmpty(), "Raveon re-enable yields while TAB remains active");
            tabPacket(bridge, tabObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, ""));
            drain();
            check(bridge.canUse(viewer) == supported, "TAB disable condition releases the supported below-name slot");
            check(objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE) == (supported ? 1 : 0),
                    "Only supported clients receive Raveon CREATE after TAB disable");
            check(supported ? !scoreboardClient.belowName.isEmpty() : scoreboardClient.belowName.isEmpty(),
                    "Legacy clients retain the floating hologram path after TAB disable");
            packets.clear();
            tabRegister(bridge);
            drain();
            check(scoreboardClient.belowName.equals("TAB-BelowName"), "TAB re-enable restores its display");
            check(objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) == (supported ? 1 : 0),
                    "TAB re-enable removes Raveon's temporary objective once");
            packets.clear();
            tabPacket(bridge, tabObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, ""));
            tabRegister(bridge);
            drain();
            check(packets.isEmpty(), "Coalesced TAB reload does not recreate Raveon's objective");
            check(!bridge.canUse(viewer) && scoreboardClient.belowName.equals("TAB-BelowName"),
                    "Coalesced TAB reload keeps the TAB display");
            tabPacket(bridge, tabObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, ""));
            drain();
            tabRegister(bridge);
            drain();
            check(objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE) == (supported ? 1 : 0)
                            && objectiveCount(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) == (supported ? 1 : 0),
                    "Interleaved TAB reload creates and retires at most one Raveon objective");
            packets.clear();
            bridge.shutdown();
            drain();
            check(packets.isEmpty() && scoreboardClient.objectives.equals(Set.of("TAB-BelowName")),
                    "Raveon shutdown preserves TAB's objective");
            check(scoreboardClient.belowName.equals("TAB-BelowName"), "Raveon shutdown preserves TAB's display");
            scoreboardClient.verifyCodecs();
            scoreboardClient = null;
        }
    }

    static void hologramTests() {
        packets.clear();
        if (version == ServerVersion.V_1_16_5) {
            check(ru.raveon.api.itemstack.SkullUtils.getSkullByBase64EncodedTextureUrl("").getType()
                    == Material.PLAYER_HEAD, "Legacy head factory links without new profile API");
        }
        PacketHologramLine line = new PacketHologramLine();
        Location location = new Location(null, 10, 65, 12);
        line.spawn(viewer, location, "§a50", 2.0);
        boolean display = version.isNewerThanOrEquals(ServerVersion.V_1_19_4);
        WrapperPlayServerSpawnEntity spawn = (WrapperPlayServerSpawnEntity) packets.get(0);
        check(spawn.getEntityType() == (display ? EntityTypes.TEXT_DISPLAY : EntityTypes.ARMOR_STAND), "Correct entity type");
        int before = packets.size();
        line.updateText(viewer, "§a50");
        line.teleport(viewer, location);
        check(packets.size() == before, "Unchanged position/text not sent");
        line.teleport(viewer, location.clone().add(1,0,0));
        check(packets.size() == before + 1, "Moved entity gets one teleport");
        line.destroy(viewer);
        before = packets.size();
        line.destroy(viewer);
        check(packets.size() == before, "Destroy idempotent");
        TrackedPlayerHologram hologram = new TrackedPlayerHologram(100);
        packets.clear();
        hologram.refreshTargetPassengers(new int[]{101, 102});
        hologram.updateForViewer(viewer, location, List.of("a","b"), 2.0, 0.25);
        int[] merged = hologram.mergePassengers(viewer.getUniqueId(), new int[]{101,102});
        check(merged.length == 4 && merged[0] == 101 && merged[1] == 102, "Real passengers preserved");
        check(hologram.mergePassengers(viewer.getUniqueId(), merged) == merged, "Passengers not duplicated");
        if (display) {
            hologram.refreshTargetPassengers(merged);
            hologram.updateForViewer(viewer, location, List.of("a"), 2.0, 0.25);
            WrapperPlayServerSetPassengers mount = (WrapperPlayServerSetPassengers) packets.get(packets.size()-1);
            check(mount.getPassengers().length == 3, "Removed fake passenger not retained as real");
            check(mount.getPassengers()[0] == 101 && mount.getPassengers()[1] == 102,
                    "Real passengers remain after line reduction");
        }
    }

    static void codecTests() {
        for (PacketWrapper<?> wrapper : List.copyOf(packets)) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                wrapper.buffer = buffer;
                wrapper.setClientVersion(version.toClientVersion());
                wrapper.write();
                Event event = new Event(wrapper, false);
                if (wrapper instanceof WrapperPlayServerSpawnEntity) new WrapperPlayServerSpawnEntity(event);
                else if (wrapper instanceof WrapperPlayServerEntityMetadata) new WrapperPlayServerEntityMetadata(event);
                else if (wrapper instanceof WrapperPlayServerSetPassengers) new WrapperPlayServerSetPassengers(event);
                else continue;
                check(!buffer.isReadable(), "Packet payload consumed: " + wrapper.getClass().getSimpleName());
            } finally {
                buffer.release();
                wrapper.buffer = null;
            }
        }
        if (version.isNewerThanOrEquals(ServerVersion.V_1_20_3)) {
            packets.clear();
            packets.add(objective("r123", WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE));
            packets.add(objective("r123", WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE));
            packets.add(new WrapperPlayServerUpdateScore("target",
                    WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, "r123", 0, null,
                    ScoreFormat.fixedScore(Component.text("50%"))));
            for (PacketWrapper<?> wrapper : packets) {
                ByteBuf buffer = Unpooled.buffer();
                try {
                    wrapper.buffer = buffer;
                    wrapper.setClientVersion(version.toClientVersion());
                    wrapper.write();
                    Event event = new Event(wrapper, false);
                    if (wrapper instanceof WrapperPlayServerScoreboardObjective) new WrapperPlayServerScoreboardObjective(event);
                    else new WrapperPlayServerUpdateScore(event);
                    check(!buffer.isReadable(), "Scoreboard payload consumed");
                } finally {
                    buffer.release();
                    wrapper.buffer = null;
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        version = ServerVersion.valueOf("V_" + args[0].replace('.','_'));
        PacketEvents.setAPI(new Api());
        user = new RecordingUser(UUID.randomUUID());
        Scoreboard scoreboard = proxy(Scoreboard.class, (p,m,a) -> defaultValue(m.getReturnType()));
        viewer = proxy(Player.class, (p,m,a) -> {
            if (m.getName().equals("getUniqueId")) return user.getUUID();
            if (m.getName().equals("getName")) return "viewer";
            if (m.getName().equals("getScoreboard")) return scoreboard;
            if (m.getName().equals("isOnline")) return true;
            return defaultValue(m.getReturnType());
        });
        Server server = proxy(Server.class, (p,m,a) -> {
            if (m.getName().equals("getLogger")) return Logger.getLogger("test");
            if (m.getName().equals("getBukkitVersion")) return args[0] + "-R0.1";
            if (m.getName().equals("getVersion") || m.getName().equals("getName")) return "test";
            return defaultValue(m.getReturnType());
        });
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null,server);
        scoreboardTests();
        tabTests();
        hologramTests();
        codecTests();
        System.out.println(args[0] + " PASS " + assertions + " assertions");
    }
}
