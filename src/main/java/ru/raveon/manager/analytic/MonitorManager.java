package ru.raveon.manager.analytic;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.raveon.api.models.monitor.AiSnapshot;
import ru.raveon.api.models.monitor.MonitorSession;
import ru.raveon.api.models.monitor.ToggleResult;
import ru.raveon.config.MainConfigManager;

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
    private static final double PROBABILITY_EPSILON = 0.0001D;

    private final Plugin plugin;
    private final MainConfigManager config;

    private final Map<UUID, MonitorSession> sessions = new HashMap<>();
    private final Map<UUID, AiSnapshot> snapshots = new HashMap<>();

    private BukkitTask updateTask;
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

    public boolean stop(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");

        MonitorSession removedSession = sessions.remove(viewer.getUniqueId());
        if (removedSession == null) {
            return false;
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
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player onlineTarget = Bukkit.getPlayer(targetId);
                if (onlineTarget != null) {
                    publish(onlineTarget, probability, buffer);
                }
            });
            return;
        }

        UUID targetId = target.getUniqueId();
        if (!isTargetMonitored(targetId)) {
            return;
        }

        double normalizedProbability = clamp(probability, 0.0D, 1.0D);
        double normalizedBuffer = Math.max(0.0D, buffer);

        snapshots.compute(targetId, (ignored, previous) -> {
            double trend = previous == null
                    ? 0.0D
                    : normalizedProbability - previous.probability();

            return new AiSnapshot(
                    normalizedProbability,
                    normalizedBuffer,
                    trend
            );
        });
    }

    public boolean isMonitoring(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        return sessions.containsKey(viewer.getUniqueId());
    }

    public void shutdown() {
        cancelUpdater();

        sessions.clear();
        snapshots.clear();
        currentTick = 0L;
    }

    public void startUpdaterIfNeeded() {
        if (updateTask != null) {
            return;
        }

        currentTick = 0L;
        updateTask = Bukkit.getScheduler().runTaskTimer(
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

        return config.getMonitorActionBarFormat()
                .replace("{player}", target.getName())
                .replace("{probability}", decimal(snapshot.probability() * 100.0D))
                .replace(
                        "{probability_color}",
                        config.getChanceColor(snapshot.probability())
                )
                .replace("{buffer}", decimal(snapshot.buffer()))
                .replace("{trend}", createTrend(snapshot.trend()));
    }

    private String createTrend(double trend) {
        String format;

        if (Math.abs(trend) <= PROBABILITY_EPSILON) {
            format = config.getMonitorTrendEqualFormat();
        } else if (trend > 0.0D) {
            format = config.getMonitorTrendUpFormat();
        } else {
            format = config.getMonitorTrendDownFormat();
        }

        return format.replace("{value}", decimal(trend * 100.0D));
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
