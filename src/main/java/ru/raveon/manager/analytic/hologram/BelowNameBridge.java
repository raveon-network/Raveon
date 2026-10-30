package ru.raveon.manager.analytic.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BelowNameBridge {
    private static final int BELOW_NAME_SLOT = 2;
    private final Map<UUID, ViewerState> states = new ConcurrentHashMap<>();

    public boolean supportsBelowName(Player viewer) {
        if (!PacketEvents.getAPI().getServerManager().getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_20_3)) return false;
        ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(viewer);
        return version != null && version.isNewerThanOrEquals(ClientVersion.V_1_20_3);
    }

    public boolean canUse(Player viewer) {
        if (!supportsBelowName(viewer)) return false;
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user == null) return false;
        ViewerState state = stateFor(viewer.getUniqueId(), user);
        synchronized (state) {
            if (!state.initialized) {
                var objective = viewer.getScoreboard().getObjective(DisplaySlot.BELOW_NAME);
                if (objective != null && state.foreignObjective.isEmpty()) {
                    state.foreignObjective = objective.getName();
                }
                state.initialized = true;
            }
            return state.foreignObjective.isEmpty();
        }
    }

    public void update(Player viewer, Map<String, String> entries) {
        if (entries.isEmpty() && !states.containsKey(viewer.getUniqueId())) return;
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user == null) return;
        ViewerState state = stateFor(viewer.getUniqueId(), user);
        synchronized (state) {
            state.desired = Map.copyOf(entries);
        }
        schedule(state);
    }

    public void observe(PacketSendEvent event) {
        boolean display = event.getPacketType() == PacketType.Play.Server.DISPLAY_SCOREBOARD;
        boolean objective = event.getPacketType() == PacketType.Play.Server.SCOREBOARD_OBJECTIVE;
        if ((!display && !objective) || event.isCancelled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        ViewerState state = stateFor(user.getUUID(), user);
        synchronized (state) {
            if (display) {
                var packet = new WrapperPlayServerDisplayScoreboard(event);
                if (packet.getPosition() != BELOW_NAME_SLOT) return;
                String name = packet.getScoreName();
                state.foreignObjective = name == null ? "" : name;
                state.initialized = true;
                if (state.created && state.objectiveName.equals(name)) {
                    state.created = false;
                    state.lastEntries.clear();
                    state.objectiveName = newObjectiveName();
                }
            } else {
                var packet = new WrapperPlayServerScoreboardObjective(event);
                String name = packet.getName();
                var mode = packet.getMode();
                if (mode == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
                    if (state.foreignObjective.equals(name)) state.foreignObjective = "";
                    if (state.objectiveName.equals(name)) {
                        state.created = false;
                        state.lastEntries.clear();
                        state.objectiveName = newObjectiveName();
                    }
                } else if (state.objectiveName.equals(name)) {
                    if (state.created && mode == WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE) {
                        removeOwned(state);
                    }
                    state.created = false;
                    state.lastEntries.clear();
                    state.foreignObjective = name;
                    state.objectiveName = newObjectiveName();
                }
            }
        }
        event.getTasksAfterSend().add(() -> schedule(state));
    }

    public void clear(Player viewer) {
        ViewerState state = states.get(viewer.getUniqueId());
        if (state == null) return;
        synchronized (state) {
            state.desired = Map.of();
        }
        schedule(state);
    }

    public void forget(UUID viewerId) {
        ViewerState state = states.remove(viewerId);
        if (state != null) {
            synchronized (state) {
                state.retired = true;
                state.desired = Map.of();
                state.lastEntries.clear();
            }
        }
    }

    public void clearAll() {
        for (ViewerState state : states.values()) {
            synchronized (state) {
                state.desired = Map.of();
            }
            schedule(state);
        }
    }

    public void shutdown() {
        clearAll();
        states.clear();
    }

    private ViewerState stateFor(UUID id, User user) {
        return states.compute(id, (ignored, previous) ->
                previous != null && previous.user == user ? previous : new ViewerState(user));
    }

    private void schedule(ViewerState state) {
        if (!state.queued.compareAndSet(false, true)) return;
        try {
            ChannelHelper.runInEventLoop(state.user.getChannel(), () -> {
                synchronized (state) {
                    state.queued.set(false);
                    if (state.retired || !ChannelHelper.isOpen(state.user.getChannel())) return;
                    if (!state.foreignObjective.isEmpty() || state.desired.isEmpty()) {
                        removeOwned(state);
                        return;
                    }
                    if (!state.created) {
                        state.user.sendPacketSilently(new WrapperPlayServerScoreboardObjective(
                                state.objectiveName,
                                WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
                                Component.empty(),
                                WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                                ScoreFormat.blankScore()));
                        state.created = true;
                        state.user.sendPacketSilently(new WrapperPlayServerDisplayScoreboard(
                                BELOW_NAME_SLOT, state.objectiveName));
                    }
                    for (String entry : state.lastEntries.keySet()) {
                        if (!state.desired.containsKey(entry)) {
                            state.user.sendPacketSilently(
                                    new WrapperPlayServerResetScore(entry, state.objectiveName));
                        }
                    }
                    for (var entry : state.desired.entrySet()) {
                        if (!entry.getValue().equals(state.lastEntries.get(entry.getKey()))) {
                            Component component = LegacyComponentSerializer.legacySection().deserialize(
                                    ChatColor.translateAlternateColorCodes('&', entry.getValue()));
                            state.user.sendPacketSilently(new WrapperPlayServerUpdateScore(
                                    entry.getKey(),
                                    WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                                    state.objectiveName, 0, null, ScoreFormat.fixedScore(component)));
                        }
                    }
                    state.lastEntries.clear();
                    state.lastEntries.putAll(state.desired);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            state.queued.set(false);
        }
    }

    private void removeOwned(ViewerState state) {
        if (!state.created) return;
        state.created = false;
        state.lastEntries.clear();
        state.user.sendPacketSilently(new WrapperPlayServerScoreboardObjective(
                state.objectiveName,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                Component.empty(), null));
    }

    private static String newObjectiveName() {
        return "rv" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private static final class ViewerState {
        private final User user;
        private final AtomicBoolean queued = new AtomicBoolean();
        private final Map<String, String> lastEntries = new HashMap<>();
        private String objectiveName = newObjectiveName();
        private String foreignObjective = "";
        private Map<String, String> desired = Map.of();
        private boolean created;
        private boolean initialized;
        private boolean retired;

        private ViewerState(User user) {
            this.user = user;
        }
    }
}
