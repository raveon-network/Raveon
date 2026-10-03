package ru.raveon.manager.analytic;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.raveon.api.models.monitor.AiSnapshot;
import ru.raveon.api.models.monitor.MonitorSession;
import ru.raveon.api.models.monitor.ToggleResult;
import ru.raveon.Raveon;
import ru.raveon.config.MainConfigManager;
import ru.raveon.utils.SchedulerUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RequiredArgsConstructor
public final class MonitorManager {
    private static final long UPDATE_PERIOD_TICKS = 2L;
    private static final long ACTION_BAR_KEEP_ALIVE_TICKS = 20L;

    private final Plugin plugin;
    private final MainConfigManager config;

    private final Map<UUID, MonitorSession> sessions = new HashMap<>();
    private final Map<UUID, AiSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, ChatSubscription> chatSubscriptions = new HashMap<>();

    private record ChatSubscription(UUID targetId, String targetName) {
    }

    private SchedulerUtils.TaskHandle updateTask;
    private long currentTick;

    public ToggleResult toggle(Player viewer, Player target) {
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target.getUniqueId();
        MonitorSession currentSession = sessions.get(viewerId);

        if (isSameTarget(currentSession, targetId)) {
            stop(viewer);
            return ToggleResult.DISABLED;
        }

        ToggleResult result = currentSession == null
                ? ToggleResult.ENABLED
                : ToggleResult.SWITCHED;

        sessions.put(viewerId, new MonitorSession(targetId, target.getName()));

        if (currentSession != null) {
            removeSnapshotIfUnused(currentSession.getTargetId());
        }

        startUpdaterIfNeeded();
        return result;
    }

    public ToggleResult toggleChat(Player viewer, Player target) {
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target == null ? null : target.getUniqueId();
        ChatSubscription current = chatSubscriptions.get(viewerId);

        if (current != null && Objects.equals(current.targetId(), targetId)) {
            chatSubscriptions.remove(viewerId);
            return ToggleResult.DISABLED;
        }

        chatSubscriptions.put(viewerId, new ChatSubscription(
                targetId,
                target == null ? config.getMonitorChatAllName() : target.getName()
        ));

        return current == null ? ToggleResult.ENABLED : ToggleResult.SWITCHED;
    }

    public boolean stop(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");

        boolean chatStopped = chatSubscriptions.remove(viewer.getUniqueId()) != null;

        MonitorSession removedSession = sessions.remove(viewer.getUniqueId());
        if (removedSession == null) {
            return chatStopped;
        }

        removeSnapshotIfUnused(removedSession.getTargetId());
        stopUpdaterIfIdle();
        return true;
    }

    public void publish(Player target, double probability, double buffer) {
        if (target == null || !Double.isFinite(probability) || !Double.isFinite(buffer)) {
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            UUID targetId = target.getUniqueId();
            SchedulerUtils.run(plugin, () -> {
                Player onlineTarget = Bukkit.getPlayer(targetId);
                if (onlineTarget != null) {
                    publish(onlineTarget, probability, buffer);
                }
            });
            return;
        }

        UUID targetId = target.getUniqueId();
        boolean actionBarMonitored = isTargetMonitored(targetId);
        boolean chatMonitored = hasChatSubscribers(targetId);

        if (!actionBarMonitored && !chatMonitored) {
            return;
        }

        double normalizedProbability = clamp(probability, 0.0D, 1.0D);
        double normalizedBuffer = Math.max(0.0D, buffer);
        double average = Raveon.INSTANCE.getViolationManager().getAverageProbability(targetId, normalizedProbability);

        AiSnapshot previous = snapshots.get(targetId);
        double trend = previous == null ? 0.0D : normalizedProbability - previous.probability();
        AiSnapshot snapshot = new AiSnapshot(normalizedProbability, average, normalizedBuffer, trend);
        snapshots.put(targetId, snapshot);

        if (!actionBarMonitored) {
            snapshots.remove(targetId);
        }

        if (chatMonitored) {
            sendChat(target, snapshot);
        }
    }

    private void sendChat(Player target, AiSnapshot snapshot) {
        String message = format(config.getMonitorChatFormat(), target, snapshot);

        Iterator<Map.Entry<UUID, ChatSubscription>> iterator = chatSubscriptions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ChatSubscription> entry = iterator.next();
            ChatSubscription subscription = entry.getValue();

            if (subscription.targetId() != null && !subscription.targetId().equals(target.getUniqueId())) {
                continue;
            }

            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) {
                iterator.remove();
                continue;
            }

            viewer.sendMessage(message);
        }
    }

    private boolean hasChatSubscribers(UUID targetId) {
        return chatSubscriptions.values().stream()
                .anyMatch(subscription -> subscription.targetId() == null
                        || subscription.targetId().equals(targetId));
    }

    public boolean isMonitoring(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        return sessions.containsKey(viewer.getUniqueId());
    }

    public void shutdown() {
        cancelUpdater();

        sessions.clear();
        chatSubscriptions.clear();
        snapshots.clear();
        currentTick = 0L;
    }

    public void startUpdaterIfNeeded() {
        if (updateTask != null) {
            return;
        }

        currentTick = 0L;
        updateTask = SchedulerUtils.runTimer(
                plugin,
                this::tick,
                1L,
                UPDATE_PERIOD_TICKS
        );
    }

    private void tick() {
        currentTick += UPDATE_PERIOD_TICKS;

        Iterator<Map.Entry<UUID, MonitorSession>> iterator =
                sessions.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, MonitorSession> entry = iterator.next();
            UUID viewerId = entry.getKey();
            MonitorSession session = entry.getValue();

            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) {
                iterator.remove();
                removeSnapshotIfUnused(session.getTargetId());
                continue;
            }

            Player target = Bukkit.getPlayer(session.getTargetId());
            if (target == null || !target.isOnline()) {
                iterator.remove();
                snapshots.remove(session.getTargetId());
                viewer.sendMessage(
                        replacePlayer(
                                config.getMonitorTargetLeftMessage(),
                                session.getTargetName()
                        )
                );
                continue;
            }

            String actionBar = createActionBar(target, snapshots.get(session.getTargetId()));
            if (!session.shouldSend(
                    actionBar,
                    currentTick,
                    ACTION_BAR_KEEP_ALIVE_TICKS
            )) {
                continue;
            }

            viewer.sendActionBar(actionBar);
            session.markSent(actionBar, currentTick);
        }

        stopUpdaterIfIdle();
    }

    private String createActionBar(Player target, AiSnapshot snapshot) {
        if (snapshot == null) {
            return replacePlayer(
                    config.getMonitorWaitingFormat(),
                    target.getName()
            );
        }

        return format(config.getMonitorActionBarFormat(), target, snapshot);
    }

    private String format(String template, Player target, AiSnapshot snapshot) {
        return template
                .replace("{player}", target.getName())
                .replace("{prob_color}", config.getChanceColor(snapshot.probability()))
                .replace("{avg_color}", config.getChanceColor(snapshot.average()))
                .replace("{probability_color}", config.getChanceColor(snapshot.probability()))
                .replace("{prob}", decimal(snapshot.probability() * 100.0D))
                .replace("{probability}", decimal(snapshot.probability() * 100.0D))
                .replace("{avg}", decimal(snapshot.average() * 100.0D))
                .replace("{buffer}", decimal(snapshot.buffer()))
                .replace("{trend}", decimal(snapshot.trend() * 100.0D));
    }

    private boolean isSameTarget(MonitorSession session, UUID targetId) {
        return session != null && session.getTargetId().equals(targetId);
    }

    private boolean isTargetMonitored(UUID targetId) {
        return sessions.values().stream()
                .anyMatch(session -> session.getTargetId().equals(targetId));
    }

    private void removeSnapshotIfUnused(UUID targetId) {
        if (!isTargetMonitored(targetId)) {
            snapshots.remove(targetId);
        }
    }

    private String replacePlayer(String message, String playerName) {
        return message.replace("{player}", playerName);
    }

    private String decimal(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void stopUpdaterIfIdle() {
        if (!sessions.isEmpty()) {
            return;
        }

        cancelUpdater();
        currentTick = 0L;
    }

    private void cancelUpdater() {
        if (updateTask == null) {
            return;
        }

        updateTask.cancel();
        updateTask = null;
    }
}
