package ru.raveon.manager.analytic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import ru.raveon.Raveon;
import ru.raveon.api.models.monitor.AiSnapshot;
import ru.raveon.api.models.monitor.MonitorModelFilter;
import ru.raveon.api.models.monitor.MonitorSession;
import ru.raveon.api.models.monitor.ToggleResult;
import ru.raveon.model.ModelId;
import ru.raveon.utils.ProbabilityUtils;
import ru.raveon.utils.SchedulerUtils;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MonitorManager implements Listener {
    private static final long UPDATE_PERIOD_TICKS = 2L;
    private static final long ACTION_BAR_KEEP_ALIVE_TICKS = 20L;

    private final Raveon plugin;
    private final java.util.Set<UUID> pendingViewers = new HashSet<>();

    public MonitorManager(Raveon plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    private final Map<UUID, MonitorSession> sessions = new HashMap<>();
    private final Map<UUID, AiSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, EnumMap<ModelId, AiSnapshot>> modelSnapshots = new HashMap<>();
    private final Map<UUID, ChatSubscription> chatSubscriptions = new HashMap<>();
    private final Map<UUID, MonitorSelection> savedSessions = new HashMap<>();
    private final Map<UUID, ChatSubscription> savedChatSubscriptions = new HashMap<>();

    private record MonitorSelection(UUID targetId, MonitorModelFilter modelFilter) {}

    private record ChatSubscription(
            UUID targetId, String targetName, MonitorModelFilter modelFilter) {}

    private SchedulerUtils.TaskHandle updateTask;
    private long currentTick;

    public synchronized ToggleResult toggle(Player viewer, Player target) {
        return toggle(viewer, target, MonitorModelFilter.ALL);
    }

    public synchronized ToggleResult toggle(Player viewer, Player target, MonitorModelFilter modelFilter) {
        Objects.requireNonNull(modelFilter, "modelFilter");
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target.getUniqueId();
        MonitorSession currentSession = sessions.get(viewerId);

        MonitorSelection saved = savedSessions.get(viewerId);
        if (saved != null && saved.targetId().equals(targetId)
                && saved.modelFilter() == modelFilter) {
            stop(viewer);
            return ToggleResult.DISABLED;
        }

        ToggleResult result = saved == null ? ToggleResult.ENABLED : ToggleResult.SWITCHED;

        sessions.put(viewerId, new MonitorSession(targetId, target.getName(), modelFilter));
        savedSessions.put(viewerId, new MonitorSelection(targetId, modelFilter));

        if (currentSession != null) {
            removeSnapshotIfUnused(currentSession.getTargetId());
        }

        startUpdaterIfNeeded();
        return result;
    }

    public synchronized ToggleResult toggleChat(Player viewer, Player target) {
        return toggleChat(viewer, target, MonitorModelFilter.ALL);
    }

    public synchronized ToggleResult toggleChat(Player viewer, Player target, MonitorModelFilter modelFilter) {
        Objects.requireNonNull(modelFilter, "modelFilter");
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target == null ? null : target.getUniqueId();
        ChatSubscription current = savedChatSubscriptions.get(viewerId);

        if (current != null
                && Objects.equals(current.targetId(), targetId)
                && current.modelFilter() == modelFilter) {
            chatSubscriptions.remove(viewerId);
            savedChatSubscriptions.remove(viewerId);
            pruneUnusedSnapshots();
            return ToggleResult.DISABLED;
        }

        chatSubscriptions.put(
                viewerId,
                new ChatSubscription(
                        targetId,
                        target == null ? plugin.getMainConfigManager().getLanguageService().text("monitor.chat.all_name") : target.getName(),
                        modelFilter));
        savedChatSubscriptions.put(viewerId, chatSubscriptions.get(viewerId));
        pruneUnusedSnapshots();

        return current == null ? ToggleResult.ENABLED : ToggleResult.SWITCHED;
    }

    public synchronized boolean stop(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");

        boolean savedMonitor = savedSessions.remove(viewer.getUniqueId()) != null;
        boolean savedChat = savedChatSubscriptions.remove(viewer.getUniqueId()) != null;
        boolean chatStopped = chatSubscriptions.remove(viewer.getUniqueId()) != null || savedChat;

        MonitorSession removedSession = sessions.remove(viewer.getUniqueId());
        if (removedSession == null) {
            pruneUnusedSnapshots();
            stopUpdaterIfIdle();
            return chatStopped || savedMonitor;
        }

        removeSnapshotIfUnused(removedSession.getTargetId());
        pruneUnusedSnapshots();
        stopUpdaterIfIdle();
        return true;
    }

    public void publish(Player target, double probability, double buffer, ModelId model) {
        if (target == null || !Double.isFinite(probability) || !Double.isFinite(buffer)) {
            return;
        }

        synchronized (this) {
            if (!isTargetMonitored(target.getUniqueId(), model)
                    && !hasChatSubscribers(target.getUniqueId(), model)) return;
        }
        SchedulerUtils.runEntity(plugin, target, () -> {
            if (target.isOnline() && plugin.isEnabled()) {
                publishOnScheduler(target, probability, buffer, model);
            }
        });
    }

    private synchronized void publishOnScheduler(
            Player target, double probability, double buffer, ModelId model) {
        UUID targetId = target.getUniqueId();
        var playerData = Raveon.INSTANCE.getPlayerDataManager().getPlayer(targetId);
        if (playerData == null) return;
        boolean actionBarMonitored = isTargetMonitored(targetId, model);
        boolean chatMonitored = hasChatSubscribers(targetId, model);

        if (!actionBarMonitored && !chatMonitored) {
            return;
        }

        double normalizedProbability = clamp(probability, 0.0D, 1.0D);
        double normalizedBuffer = Math.max(0.0D, buffer);
        double average =
                Raveon.INSTANCE
                        .getViolationManager()
                        .getAverageProbability(targetId, normalizedProbability, model);

        var perModel =
                modelSnapshots.computeIfAbsent(targetId, ignored -> new EnumMap<>(ModelId.class));
        AiSnapshot previous = perModel.get(model);
        double trend = previous == null ? 0.0D : normalizedProbability - previous.probability();
        AiSnapshot snapshot =
                new AiSnapshot(normalizedProbability, average, normalizedBuffer, trend, model);
        perModel.put(model, snapshot);
        snapshots.put(targetId, snapshot);

        if (!isTargetMonitored(targetId)) {
            snapshots.remove(targetId);
            if (!hasChatSubscribers(targetId)) modelSnapshots.remove(targetId);
        }

        if (chatMonitored) {
            sendChat(target, snapshot);
        }
    }

    private void sendChat(Player target, AiSnapshot snapshot) {
        String message = format(plugin.getMainConfigManager().getLanguageService().text("monitor.chat.format"), target.getName(), snapshot);

        for (Map.Entry<UUID, ChatSubscription> entry : chatSubscriptions.entrySet()) {
            ChatSubscription subscription = entry.getValue();

            if (subscription.targetId() != null
                    && !subscription.targetId().equals(target.getUniqueId())) {
                continue;
            }

            if (!subscription.modelFilter().accepts(snapshot.model())) {
                continue;
            }

            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) {
                continue;
            }

            SchedulerUtils.runEntity(plugin, viewer, () -> {
                synchronized (this) {
                    if (!viewer.isOnline() || chatSubscriptions.get(entry.getKey()) != subscription) return;
                    viewer.sendMessage(message);
                }
            });
        }
    }

    private boolean hasChatSubscribers(UUID targetId) {
        return chatSubscriptions.values().stream()
                .anyMatch(
                        subscription ->
                                subscription.targetId() == null
                                        || subscription.targetId().equals(targetId));
    }

    private boolean hasChatSubscribers(UUID targetId, ModelId model) {
        return chatSubscriptions.values().stream()
                .anyMatch(
                        subscription ->
                                (subscription.targetId() == null
                                                || subscription.targetId().equals(targetId))
                                        && subscription.modelFilter().accepts(model));
    }

    public synchronized boolean isMonitoring(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        return savedSessions.containsKey(viewer.getUniqueId());
    }

    public synchronized void restoreOnJoin(Player player) {
        restoreViewer(player);
        UUID targetId = player.getUniqueId();
        var viewersToRestore = new HashSet<UUID>();
        savedSessions.forEach((viewerId, selection) -> {
            if (selection.targetId().equals(targetId)) viewersToRestore.add(viewerId);
        });
        savedChatSubscriptions.forEach((viewerId, selection) -> {
            if (targetId.equals(selection.targetId())) viewersToRestore.add(viewerId);
        });
        viewersToRestore.remove(targetId);
        for (UUID viewerId : viewersToRestore) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) SchedulerUtils.runEntity(plugin, viewer, () -> {
                synchronized (this) {
                    if (plugin.isEnabled() && viewer.isOnline()) restoreViewer(viewer);
                }
            });
        }
    }

    private void restoreViewer(Player viewer) {
        UUID id = viewer.getUniqueId();
        if (!viewer.isOnline() || !viewer.hasPermission("raveon.command.monitor")) {
            sessions.remove(id);
            chatSubscriptions.remove(id);
            pruneUnusedSnapshots();
            stopUpdaterIfIdle();
            return;
        }
        MonitorSelection selection = savedSessions.get(id);
        if (selection != null && !sessions.containsKey(id)) {
            Player target = Bukkit.getPlayer(selection.targetId());
            if (target != null && target.isOnline())
                sessions.put(id, new MonitorSession(target.getUniqueId(), target.getName(), selection.modelFilter()));
        }
        ChatSubscription chat = savedChatSubscriptions.get(id);
        if (chat != null && !chatSubscriptions.containsKey(id)) {
            Player target = chat.targetId() == null ? null : Bukkit.getPlayer(chat.targetId());
            if (chat.targetId() == null || (target != null && target.isOnline()))
                chatSubscriptions.put(id, new ChatSubscription(chat.targetId(),
                        target == null ? plugin.getLanguageService().text("monitor.chat.all_name") : target.getName(),
                        chat.modelFilter()));
        }
        if (!sessions.isEmpty()) startUpdaterIfNeeded();
    }

    public synchronized void shutdown() {
        cancelUpdater();

        sessions.clear();
        chatSubscriptions.clear();
        savedSessions.clear();
        savedChatSubscriptions.clear();
        snapshots.clear();
        modelSnapshots.clear();
        currentTick = 0L;
        pendingViewers.clear();
    }

    public synchronized void startUpdaterIfNeeded() {
        if (updateTask != null) {
            return;
        }

        currentTick = 0L;
        updateTask = SchedulerUtils.runTimer(plugin, this::tick, 1L, UPDATE_PERIOD_TICKS);
    }

    private synchronized void tick() {
        currentTick += UPDATE_PERIOD_TICKS;
        Iterator<Map.Entry<UUID, MonitorSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MonitorSession> entry = iterator.next();
            UUID viewerId = entry.getKey();
            MonitorSession session = entry.getValue();
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null) {
                iterator.remove();
                chatSubscriptions.remove(viewerId);
                continue;
            }
            if (!pendingViewers.add(viewerId)) continue;
            if (!SchedulerUtils.runEntity(plugin, viewer, () -> updateViewer(viewer, session),
                    () -> releasePending(viewerId))) {
                pendingViewers.remove(viewerId);
            }
        }
        pruneUnusedSnapshots();
        stopUpdaterIfIdle();
    }

    private synchronized void releasePending(UUID viewerId) {
        pendingViewers.remove(viewerId);
    }

    private synchronized void updateViewer(Player viewer, MonitorSession session) {
        UUID viewerId = viewer.getUniqueId();
        try {
            if (sessions.get(viewerId) != session) return;
            if (!viewer.isOnline()) {
                sessions.remove(viewerId);
                chatSubscriptions.remove(viewerId);
                pruneUnusedSnapshots();
                stopUpdaterIfIdle();
                return;
            }
            if (Bukkit.getPlayer(session.getTargetId()) == null) {
                sessions.remove(viewerId);
                pruneUnusedSnapshots();
                stopUpdaterIfIdle();
                viewer.sendMessage(replacePlayer(
                        plugin.getMainConfigManager().getLanguageService().text("monitor.target_left"),
                        session.getTargetName()));
                return;
            }
            String actionBar = createActionBar(session.getTargetName(), snapshotFor(session));
            if (session.shouldSend(actionBar, currentTick, ACTION_BAR_KEEP_ALIVE_TICKS)) {
                viewer.sendActionBar(actionBar);
                session.markSent(actionBar, currentTick);
            }
        } finally {
            pendingViewers.remove(viewerId);
        }
    }

    @EventHandler
    public synchronized void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.remove(id);
        chatSubscriptions.remove(id);
        pendingViewers.remove(id);
        sessions.entrySet().removeIf(entry -> {
            MonitorSession session = entry.getValue();
            if (!session.getTargetId().equals(id)) return false;
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                String message = replacePlayer(
                        plugin.getMainConfigManager().getLanguageService().text("monitor.target_left"),
                        session.getTargetName());
                SchedulerUtils.runEntity(plugin, viewer, () -> {
                    if (viewer.isOnline()) viewer.sendMessage(message);
                });
            }
            return true;
        });
        chatSubscriptions.values().removeIf(subscription -> id.equals(subscription.targetId()));
        snapshots.remove(id);
        modelSnapshots.remove(id);
        pruneUnusedSnapshots();
        stopUpdaterIfIdle();
    }

    private AiSnapshot snapshotFor(MonitorSession session) {
        if (session.getModelFilter() == MonitorModelFilter.ALL)
            return snapshots.get(session.getTargetId());
        var perModel = modelSnapshots.get(session.getTargetId());
        if (perModel == null) return null;
        return perModel.get(
                session.getModelFilter() == MonitorModelFilter.PRO ? ModelId.PRO : ModelId.ULTRA);
    }

    private String createActionBar(String targetName, AiSnapshot snapshot) {
        if (snapshot == null) {
            return replacePlayer(plugin.getMainConfigManager().getLanguageService().text("monitor.action_bar.waiting"), targetName);
        }

        String template = plugin.getMainConfigManager().getLanguageService().text("monitor.action_bar.format");
        if (!template.contains("{model}")) {
            template = "\u00a77[{model}] \u00a7r" + template;
        }
        return format(template, targetName, snapshot);
    }

    private String format(String template, String targetName, AiSnapshot snapshot) {
        return template.replace("{model}", snapshot.model().displayName())
                .replace("{player}", targetName)
                .replace("{prob_color}", plugin.getMainConfigManager().getChanceColor(snapshot.probability()))
                .replace("{avg_color}", plugin.getMainConfigManager().getChanceColor(snapshot.average()))
                .replace("{probability_color}", plugin.getMainConfigManager().getChanceColor(snapshot.probability()))
                .replace("{prob}", ProbabilityUtils.formatPercent(snapshot.probability()))
                .replace("{probability}", ProbabilityUtils.formatPercent(snapshot.probability()))
                .replace("{avg}", ProbabilityUtils.formatPercent(snapshot.average()))
                .replace("{trend}", createTrend(snapshot.trend()))
                .replace("{buffer}", decimal(snapshot.buffer()));
    }

    private String createTrend(double trend) {
        int trendPercent = ProbabilityUtils.toPercent(trend);
        String format;

        if (trendPercent == 0) {
            format = plugin.getMainConfigManager().getLanguageService().text("monitor.action_bar.trend.equal");
        } else if (trendPercent > 0) {
            format = plugin.getMainConfigManager().getLanguageService().text("monitor.action_bar.trend.up");
        } else {
            format = plugin.getMainConfigManager().getLanguageService().text("monitor.action_bar.trend.down");
        }

        return format.replace("{value}", String.valueOf(trendPercent));
    }

    private boolean isTargetMonitored(UUID targetId) {
        return sessions.values().stream()
                .anyMatch(session -> session.getTargetId().equals(targetId));
    }

    private boolean isTargetMonitored(UUID targetId, ModelId model) {
        return sessions.values().stream()
                .anyMatch(
                        session ->
                                session.getTargetId().equals(targetId)
                                        && session.getModelFilter().accepts(model));
    }

    private void removeSnapshotIfUnused(UUID targetId) {
        if (!isTargetMonitored(targetId)) {
            snapshots.remove(targetId);
            if (!hasChatSubscribers(targetId)) modelSnapshots.remove(targetId);
        }
    }

    private void pruneUnusedSnapshots() {
        var targets = new HashSet<>(modelSnapshots.keySet());
        targets.addAll(snapshots.keySet());
        for (UUID targetId : targets) removeSnapshotIfUnused(targetId);
    }

    private String replacePlayer(String message, String playerName) {
        return message.replace("{player}", playerName).replace("{model}", "—");
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
