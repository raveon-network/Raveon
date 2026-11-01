package ru.raveon.manager.analytic.hologram;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ru.raveon.Raveon;
import ru.raveon.config.anticheat.HologramConfigManager;
import ru.raveon.model.ModelId;
import ru.raveon.utils.ProbabilityUtils;
import ru.raveon.utils.SchedulerUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HologramManager {
    private final Map<UUID, Map<ModelId, HologramText>> textByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, TargetSnapshot> targets = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> targetIdByEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersByTarget = new ConcurrentHashMap<>();
    private final Set<UUID> pendingCaptures = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedLineRefreshes = ConcurrentHashMap.newKeySet();
    private final Set<UUID> enabledViewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> viewerPreferences = new ConcurrentHashMap<>();
    private final BelowNameBridge belowNameBridge = new BelowNameBridge();
    private SchedulerUtils.TaskHandle updateTask;
    private volatile boolean running;
    private volatile long rotationTicks;
    private volatile ModelId displayedModel;

    public void start() {
        if (updateTask != null) updateTask.cancel();
        running = true;
        rotationTicks = 0L;
        displayedModel = null;
        updateTask = SchedulerUtils.runTimer(Raveon.INSTANCE, this::tick, 1L, 1L);
    }

    private void tick() {
        if (!running) return;
        long tick = ++rotationTicks;
        HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();
        ModelId selected = selectModel(config);
        boolean changed = selected != displayedModel;
        displayedModel = selected;
        if (tick % 10L == 0) updateVisibleHolograms();
        else if (changed)
            for (UUID viewerId : viewers.keySet()) scheduleViewer(viewerId, true, null);
    }

    private ModelId selectModel(HologramConfigManager config) {
        if (config == null || !config.isEnabled()) return null;
        var checks = Raveon.INSTANCE.getChecksConfigManager();
        var dispatcher = Raveon.INSTANCE.getAnalyzeBatchDispatcher();
        List<ModelId> models = java.util.Arrays.stream(ModelId.values())
                .filter(checks::isModelEnabled)
                .filter(dispatcher::isModelAvailable)
                .toList();
        if (models.isEmpty()) return null;
        int index = Math.floorMod(rotationTicks / config.getModelRotationIntervalTicks(), models.size());
        return models.get(index);
    }

    public void stop() {
        running = false;
        displayedModel = null;
        rotationTicks = 0L;
        if (updateTask != null) updateTask.cancel();
        updateTask = null;
        for (ViewerState state : viewers.values()) clearState(state);
        viewers.clear();
        viewersByTarget.clear();
        enabledViewers.clear();
        viewerPreferences.clear();
        synchronized (targets) { targets.clear(); }
        targetIdByEntityId.clear();
        textByTarget.clear();
        pendingCaptures.clear();
        requestedLineRefreshes.clear();
        belowNameBridge.shutdown();
    }

    public boolean hasHologramsEnabled(UUID viewerId) {
        return enabledViewers.contains(viewerId);
    }

    public void restoreSession(Player player) {
        UUID id = player.getUniqueId();
        if (!player.hasPermission("raveon.command.holograms")) {
            enabledViewers.remove(id);
            removeViewer(player);
            return;
        }
        Boolean enabled = viewerPreferences.get(id);
        if (enabled != null) setHologramsEnabled(id, enabled, true);
        else if (player.hasPermission("raveon.command.holograms.enable-on-join"))
            setHologramsEnabled(id, true, true);
    }

    public boolean toggleHolograms(UUID viewerId, boolean silent) {
        boolean enabled = !viewerPreferences.getOrDefault(viewerId, hasHologramsEnabled(viewerId));
        setHologramsEnabled(viewerId, enabled, silent);
        return enabled;
    }

    public void setHologramsEnabled(UUID viewerId, boolean enabled, boolean silent) {
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) return;
        viewerPreferences.put(viewerId, enabled);
        if (enabled) {
            enabledViewers.add(viewerId);
            viewers.computeIfAbsent(viewerId, ignored -> new ViewerState(viewer));
        } else {
            enabledViewers.remove(viewerId);
        }
        SchedulerUtils.runEntity(Raveon.INSTANCE, viewer, () -> {
            if (!viewer.isOnline()) return;
            if (!enabled) removeViewer(viewer);
            if (!silent) {
                viewer.sendMessage(enabled
                        ? Raveon.INSTANCE.getMainConfigManager().getLanguageService().text("holograms-enabled")
                        : Raveon.INSTANCE.getMainConfigManager().getLanguageService().text("holograms-disabled"));
            }
        });
        if (enabled) scheduleViewer(viewerId, true, null);
    }

    public void handleLeave(Player player) {
        UUID id = player.getUniqueId();
        enabledViewers.remove(id);
        ViewerState state = viewers.remove(id);
        if (state != null) clearState(state);
        belowNameBridge.forget(id);
        hideTarget(id);
    }

    public void handleWorldChange(Player player) {
        removeViewer(player);
        hideTarget(player.getUniqueId());
        requestCapture(player, true);
    }

    public void handleConfigReload() {
        textByTarget.clear();
        for (ViewerState state : viewers.values()) {
            state.reset.set(true);
            scheduleViewer(state.viewer.getUniqueId(), true, null);
        }
        belowNameBridge.clearAll();
    }

    public boolean needsLegacyMovement(UUID targetId) {
        if (!running || PacketHologramLine.usesDisplayEntities() || enabledViewers.isEmpty()) return false;
        HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();
        Set<UUID> targetViewers = viewersByTarget.get(targetId);
        return config != null && config.isEnabled() && targetViewers != null && !targetViewers.isEmpty();
    }

    public void handleMovement(Player target) {
        if (needsLegacyMovement(target.getUniqueId())) requestCapture(target, false);
    }

    public void removeViewer(Player viewer) {
        ViewerState state = viewers.get(viewer.getUniqueId());
        if (state == null) return;
        state.reset.set(true);
        scheduleViewer(viewer.getUniqueId(), true, null);
    }

    public int[] mergeOutgoingPassengers(UUID viewerId, int entityId, int[] passengers) {
        ViewerState state = viewers.get(viewerId);
        UUID targetId = targetIdByEntityId.get(entityId);
        if (state == null || targetId == null) return passengers;
        synchronized (state) {
            TrackedPlayerHologram hologram = state.holograms.get(targetId);
            if (hologram == null) return passengers;
            hologram.refreshTargetPassengers(passengers);
            return hologram.mergePassengers(viewerId, passengers);
        }
    }

    public void handleTargetSpawned(Player viewer, int entityId) {
        ViewerState state = viewers.get(viewer.getUniqueId());
        if (state == null) return;
        state.hiddenEntities.remove(entityId);
        UUID targetId = targetIdByEntityId.get(entityId);
        if (targetId != null) {
            state.respawnedTargets.add(targetId);
            scheduleViewer(viewer.getUniqueId(), false, targetId);
        }
    }

    public void handleEntitiesDestroyed(Player viewer, int[] entityIds) {
        ViewerState state = viewers.get(viewer.getUniqueId());
        if (state == null) return;
        for (int entityId : entityIds) {
            UUID targetId = targetIdByEntityId.get(entityId);
            if (targetId == null) continue;
            state.hiddenEntities.add(entityId);
            scheduleViewer(viewer.getUniqueId(), false, targetId);
        }
    }

    public void observeScoreboard(PacketSendEvent event) {
        belowNameBridge.observe(event);
    }

    public void handleAnalysisUpdate(UUID targetId) {
        if (!running || enabledViewers.isEmpty()) return;
        refreshCachedLines(targetId);
        for (UUID viewerId : viewers.keySet()) scheduleViewer(viewerId, false, targetId);
    }

    public void refreshCachedLines(UUID targetId) {
        HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();
        if (config == null || !config.isEnabled()) {
            textByTarget.remove(targetId);
            return;
        }
        var manager = Raveon.INSTANCE.getViolationManager();
        Map<ModelId, HologramText> texts = new java.util.EnumMap<>(ModelId.class);
        for (ModelId model : ModelId.values()) {
            var snapshot = manager.getAnalysisSnapshot(targetId, model);
            var probabilities = manager.getLocalProbabilities(targetId, model);
            boolean hasResult = !probabilities.isEmpty();
            double average = probabilities.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(snapshot.probability());
            String floating = applyPlaceholders(config.getFloatingLine(model), model, config,
                    snapshot.probability(), average, snapshot.buffer(), hasResult);
            String below = applyPlaceholders(config.getBelowNameLine(), model, config,
                    snapshot.probability(), average, snapshot.buffer(), hasResult);
            texts.put(model, new HologramText(List.of(floating), below));
        }
        textByTarget.put(targetId, Map.copyOf(texts));
    }

    private void updateVisibleHolograms() {
        if (!running) return;
        HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();
        if (config == null || !config.isEnabled() || enabledViewers.isEmpty()) {
            for (UUID viewerId : viewers.keySet()) scheduleViewer(viewerId, true, null);
            return;
        }
        Set<UUID> onlineIds = new HashSet<>();
        for (Player target : Bukkit.getOnlinePlayers()) {
            onlineIds.add(target.getUniqueId());
            requestCapture(target, true);
        }
        for (UUID targetId : targets.keySet()) {
            if (!onlineIds.contains(targetId)) hideTarget(targetId);
        }
        for (UUID viewerId : viewers.keySet()) {
            if (!onlineIds.contains(viewerId)) {
                ViewerState state = viewers.remove(viewerId);
                if (state != null) clearState(state);
                enabledViewers.remove(viewerId);
                belowNameBridge.forget(viewerId);
            } else {
                scheduleViewer(viewerId, true, null);
            }
        }
    }

    private void requestCapture(Player target, boolean refreshLines) {
        UUID id = target.getUniqueId();
        if (!running) return;
        if (refreshLines) requestedLineRefreshes.add(id);
        if (!pendingCaptures.add(id)) return;
        if (!SchedulerUtils.runEntity(Raveon.INSTANCE, target, () -> {
            try {
                if (!running || !target.isOnline()) return;
                if (requestedLineRefreshes.remove(id) || !textByTarget.containsKey(id)) {
                    refreshCachedLines(id);
                }
                Location location = target.getLocation();
                TargetSnapshot snapshot = new TargetSnapshot(target, target.getName(),
                        target.getEntityId(), location, location.getWorld().getUID(),
                        target.getPassengers().stream().mapToInt(Entity::getEntityId).toArray());
                synchronized (targets) {
                    if (!running) return;
                    TargetSnapshot previous = targets.put(id, snapshot);
                    if (previous != null && previous.entityId != snapshot.entityId) {
                        targetIdByEntityId.remove(previous.entityId, id);
                        for (ViewerState state : viewers.values()) {
                            state.hiddenEntities.remove(previous.entityId);
                        }
                    }
                    targetIdByEntityId.put(snapshot.entityId, id);
                }
                Set<UUID> targetViewers = viewersByTarget.get(id);
                if (targetViewers != null) {
                    for (UUID viewerId : targetViewers) scheduleViewer(viewerId, false, id);
                }
            } finally {
                pendingCaptures.remove(id);
            }
        }, () -> pendingCaptures.remove(id))) pendingCaptures.remove(id);
    }

    private void scheduleViewer(UUID id, boolean full, UUID targetId) {
        ViewerState state = viewers.get(id);
        if (state == null || !running) return;
        if (full) state.fullRefresh.set(true);
        if (targetId != null) state.dirtyTargets.add(targetId);
        if (!state.queued.compareAndSet(false, true)) return;
        if (!SchedulerUtils.runEntity(Raveon.INSTANCE, state.viewer, () -> {
            try {
                if (!running || viewers.get(id) != state || !state.viewer.isOnline()) return;
                render(state);
            } finally {
                state.queued.set(false);
                if (state.fullRefresh.get() || !state.dirtyTargets.isEmpty()) {
                    scheduleViewer(id, false, null);
                }
            }
        }, () -> state.queued.set(false))) state.queued.set(false);
    }

    private void render(ViewerState state) {
        synchronized (state) {
            HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();
            if (state.reset.getAndSet(false)) clearState(state);
            if (config == null || !config.isEnabled()
                    || !enabledViewers.contains(state.viewer.getUniqueId())) {
                clearState(state);
                belowNameBridge.clear(state.viewer);
                state.fullRefresh.set(false);
                state.dirtyTargets.clear();
                if (!enabledViewers.contains(state.viewer.getUniqueId())) {
                    viewers.remove(state.viewer.getUniqueId(), state);
                }
                return;
            }
            boolean full = state.fullRefresh.getAndSet(false);
            Set<UUID> changed = new HashSet<>(state.dirtyTargets);
            state.dirtyTargets.removeAll(changed);
            boolean belowName = config.isBelowNameEnabled() && belowNameBridge.canUse(state.viewer);
            if (full || belowName != state.usingBelowName) {
                changed.addAll(targets.keySet());
                changed.addAll(state.holograms.keySet());
                changed.addAll(state.belowEntries.keySet());
            }
            state.usingBelowName = belowName;
            ModelId selected = displayedModel;
            Location viewerLocation = state.viewer.getLocation();
            UUID viewerWorld = viewerLocation.getWorld().getUID();
            for (UUID targetId : changed) {
                TargetSnapshot target = targets.get(targetId);
                boolean visible = target != null
                        && !state.hiddenEntities.contains(target.entityId)
                        && viewerWorld.equals(target.worldId)
                        && state.viewer.canSee(target.player)
                        && viewerLocation.distanceSquared(target.location) <= 900.0D;
                if (!visible) {
                    removeTarget(state, targetId);
                    continue;
                }
                HologramText text = selected == null ? null
                        : textByTarget.getOrDefault(targetId, Map.of()).get(selected);
                String belowText = text == null ? null : text.below();
                boolean hasBelowName = belowName && belowText != null && !belowText.isEmpty();
                if (hasBelowName) state.belowEntries.put(targetId, new BelowEntry(target.name, belowText));
                else state.belowEntries.remove(targetId);
                if (!hasBelowName || config.isAlsoShowHologramWithBelowName()) {
                    showHologram(state, targetId, target, config, text);
                } else {
                    removeHologram(state, targetId);
                }
            }
            Map<String, String> entries = new HashMap<>();
            for (BelowEntry entry : state.belowEntries.values()) entries.put(entry.name, entry.text);
            belowNameBridge.update(state.viewer, entries);
        }
    }

    private void showHologram(ViewerState state, UUID targetId, TargetSnapshot target,
            HologramConfigManager config, HologramText text) {
        List<String> lines = text == null ? List.of() : text.lines();
        if (lines == null || lines.isEmpty()) {
            removeHologram(state, targetId);
            return;
        }
        TrackedPlayerHologram hologram = state.holograms.get(targetId);
        boolean respawned = state.respawnedTargets.remove(targetId);
        if (hologram != null && (hologram.getTargetEntityId() != target.entityId || respawned)) {
            removeHologram(state, targetId);
            hologram = null;
        }
        if (hologram == null) {
            hologram = new TrackedPlayerHologram(target.entityId);
            hologram.refreshTargetPassengers(target.passengers);
            state.holograms.put(targetId, hologram);
        }
        hologram.updateForViewer(state.viewer, target.location, lines,
                config.getOffset(), config.getLineSpacing());
        if (!PacketHologramLine.usesDisplayEntities()) {
            hologram.teleportForViewer(state.viewer, target.location,
                    config.getOffset(), config.getLineSpacing());
        }
        viewersByTarget.computeIfAbsent(targetId, ignored -> ConcurrentHashMap.newKeySet())
                .add(state.viewer.getUniqueId());
    }

    private void removeHologram(ViewerState state, UUID targetId) {
        TrackedPlayerHologram hologram = state.holograms.remove(targetId);
        if (hologram != null) hologram.destroyForViewer(state.viewer);
        viewersByTarget.computeIfPresent(targetId, (ignored, targetViewers) -> {
            targetViewers.remove(state.viewer.getUniqueId());
            return targetViewers.isEmpty() ? null : targetViewers;
        });
        state.respawnedTargets.remove(targetId);
    }

    private void removeTarget(ViewerState state, UUID targetId) {
        removeHologram(state, targetId);
        state.belowEntries.remove(targetId);
    }

    private void clearState(ViewerState state) {
        synchronized (state) {
            for (UUID targetId : new ArrayList<>(state.holograms.keySet())) removeHologram(state, targetId);
            state.belowEntries.clear();
            state.respawnedTargets.clear();
        }
    }

    private void hideTarget(UUID targetId) {
        TargetSnapshot previous = targets.remove(targetId);
        if (previous != null) targetIdByEntityId.remove(previous.entityId, targetId);
        textByTarget.remove(targetId);
        requestedLineRefreshes.remove(targetId);
        for (ViewerState state : viewers.values()) {
            if (previous != null) state.hiddenEntities.remove(previous.entityId);
            scheduleViewer(state.viewer.getUniqueId(), false, targetId);
        }
    }

    private String applyPlaceholders(String template, ModelId model, HologramConfigManager hologram,
            double probability, double average, double buffer, boolean hasResult) {
        if (template.isEmpty()) return "";
        String normalized = template.replace("[" + model.displayName() + "]", "[{model}]");
        if (!normalized.contains("{model}")) normalized = "\u00a77[{model}] " + normalized;
        var config = Raveon.INSTANCE.getMainConfigManager();
        String probabilityColor = hasResult ? config.getChanceColor(probability) : "\u00a77";
        String averageColor = hasResult ? config.getChanceColor(average) : "\u00a77";
        return normalized.replace("{prob_color}", probabilityColor)
                .replace("{probability_color}", probabilityColor)
                .replace("{avg_color}", averageColor)
                .replace("{prob}", hasResult ? ProbabilityUtils.formatPercent(probability) : "--")
                .replace("{avg}", hasResult ? ProbabilityUtils.formatPercent(average) : "--")
                .replace("{buffer}", hasResult ? String.format(Locale.ROOT, "%.2f", buffer) : "--")
                .replace("{model}", hologram.getModelName(model));
    }

    private record HologramText(List<String> lines, String below) {}

    private record TargetSnapshot(Player player, String name, int entityId, Location location,
            UUID worldId, int[] passengers) {}

    private record BelowEntry(String name, String text) {}

    private static final class ViewerState {
        private final Player viewer;
        private final Map<UUID, TrackedPlayerHologram> holograms = new HashMap<>();
        private final Map<UUID, BelowEntry> belowEntries = new HashMap<>();
        private final Set<Integer> hiddenEntities = ConcurrentHashMap.newKeySet();
        private final Set<UUID> dirtyTargets = ConcurrentHashMap.newKeySet();
        private final Set<UUID> respawnedTargets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean queued = new AtomicBoolean();
        private final AtomicBoolean fullRefresh = new AtomicBoolean();
        private final AtomicBoolean reset = new AtomicBoolean();
        private boolean usingBelowName;

        private ViewerState(Player viewer) {
            this.viewer = viewer;
        }
    }
}
