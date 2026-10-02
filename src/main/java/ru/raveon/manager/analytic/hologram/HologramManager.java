package ru.raveon.manager.analytic.hologram;

import lombok.Getter;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.raveon.Raveon;
import ru.raveon.config.anticheat.HologramConfigManager;
import ru.raveon.manager.anticheat.PlayerAnalysisSnapshot;
import ru.raveon.utils.SchedulerUtils;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public final class HologramManager {
    private static final DecimalFormat PROB_FORMAT = new DecimalFormat("0.0000");
    private static final DecimalFormat BUFFER_FORMAT = new DecimalFormat("0.00");

    private final Map<UUID, List<String>> hologramLinesByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, String> belowNameLineByTarget = new ConcurrentHashMap<>();

    private final Map<UUID, TrackedPlayerHologram> hologramsByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersByTarget = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> targetIdByEntityId = new ConcurrentHashMap<>();
    private final Set<UUID> enabledViewers = ConcurrentHashMap.newKeySet();

    private final BelowNameBridge belowNameBridge = new BelowNameBridge();
    private final Map<UUID, BelowNameViewerState> belowNameStateByViewer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> belowNameViewersByTarget = new ConcurrentHashMap<>();

    private SchedulerUtils.TaskHandle updateTask;

    public void start() {
        stop();
        updateTask = SchedulerUtils.runTimer(
                Raveon.INSTANCE,
                this::updateVisibleHolograms,
                10L,
                10L
        );
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }

        clearAllHolograms();
        clearAllBelowName();
        hologramLinesByTarget.clear();
        belowNameLineByTarget.clear();
    }

    public boolean hasHologramsEnabled(@NonNull UUID viewerId) {
        return enabledViewers.contains(viewerId);
    }

    public boolean toggleHolograms(@NonNull UUID viewerId, boolean silent) {
        boolean enabled = !hasHologramsEnabled(viewerId);
        setHologramsEnabled(viewerId, enabled, silent);
        return enabled;
    }

    public void setHologramsEnabled(@NonNull UUID viewerId, boolean enabled) {
        setHologramsEnabled(viewerId, enabled, false);
    }

    public void setHologramsEnabled(@NonNull UUID viewerId, boolean enabled, boolean silent) {
        Player viewer = Bukkit.getPlayer(viewerId);

        if (enabled) {
            enabledViewers.add(viewerId);
        } else {
            enabledViewers.remove(viewerId);

            if (viewer != null && viewer.isOnline()) {
                removeViewer(viewer);
            }
        }

        if (!silent && viewer != null && viewer.isOnline()) {
            viewer.sendMessage(enabled
                    ? Raveon.INSTANCE.getMainConfigManager().getHologramsEnabledMessage()
                    : Raveon.INSTANCE.getMainConfigManager().getHologramsDisableMessage());
        }
    }

    public void handleLeave(@NonNull Player player) {
        UUID playerId = player.getUniqueId();

        hideTarget(playerId);
        removeViewer(player);

        hologramLinesByTarget.remove(playerId);
        belowNameLineByTarget.remove(playerId);
        // Like alerts and verbose, the toggle is per session and must not keep UUIDs of everyone who ever joined.
        enabledViewers.remove(playerId);
    }

    public void handleQuit(@NonNull UUID targetId) {
        hideTarget(targetId);
        hologramLinesByTarget.remove(targetId);
        belowNameLineByTarget.remove(targetId);
    }

    /**
     * Respawns every hologram and below-name entry so a reloaded config (offset, spacing,
     * templates, below_name toggle) is applied to already visible lines.
     */
    public void handleConfigReload() {
        clearAllHolograms();
        clearAllBelowName();
        hologramLinesByTarget.clear();
        belowNameLineByTarget.clear();
    }

    public void handleWorldChange(@NonNull Player player) {
        removeViewer(player);
        hideTarget(player.getUniqueId());
    }

    /**
     * Only needed for legacy armor stand holograms; display holograms ride the target and follow it client-side.
     * Below-name never needs this: it is not tied to a spatial position.
     */
    public void handleMovement(@NonNull Player target) {
        if (PacketHologramLine.usesDisplayEntities() || !target.isOnline()) {
            return;
        }

        HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();
        if (config == null || !config.isEnabled()) {
            clearAllHolograms();
            return;
        }

        UUID targetId = target.getUniqueId();
        TrackedPlayerHologram hologram = hologramsByTarget.get(targetId);
        Set<UUID> currentViewers = viewersByTarget.get(targetId);

        if (hologram == null || currentViewers == null || currentViewers.isEmpty()) {
            return;
        }

        Location targetLocation = target.getLocation();

        for (UUID viewerId : new HashSet<>(currentViewers)) {
            Player viewer = Bukkit.getPlayer(viewerId);

            if (!canViewerSeeTarget(viewer, target)) {
                if (viewer != null && viewer.isOnline()) {
                    hologram.destroyForViewer(viewer);
                }

                currentViewers.remove(viewerId);
                continue;
            }

            hologram.teleportForViewer(viewer, targetLocation, config.getOffset(), config.getLineSpacing());
        }

        cleanupEmptyTarget(targetId, currentViewers);
    }

    public void removeViewer(@NonNull Player viewer) {
        UUID viewerId = viewer.getUniqueId();

        for (Map.Entry<UUID, Set<UUID>> entry : new ArrayList<>(viewersByTarget.entrySet())) {
            UUID targetId = entry.getKey();
            Set<UUID> viewers = entry.getValue();

            if (!viewers.remove(viewerId)) {
                continue;
            }

            TrackedPlayerHologram hologram = hologramsByTarget.get(targetId);
            if (hologram != null) {
                hologram.destroyForViewer(viewer);
            }

            cleanupEmptyTarget(targetId, viewers);
        }

        removeBelowNameViewer(viewer);
    }

    /**
     * Called from the netty thread for outgoing SET_PASSENGERS: keeps hologram lines mounted
     * when the server rewrites the target's passenger list.
     */
    public int[] mergeOutgoingPassengers(@NonNull UUID viewerId, int vehicleEntityId, @NonNull int[] passengers) {
        TrackedPlayerHologram hologram = findByEntityId(vehicleEntityId);

        if (hologram == null || !hologram.isShownTo(viewerId)) {
            return passengers;
        }

        return hologram.mergePassengers(viewerId, passengers);
    }

    /**
     * The target entity was (re)spawned for the viewer, e.g. after respawn or re-entering tracking range.
     */
    public void handleTargetSpawned(@NonNull Player viewer, int targetEntityId) {
        TrackedPlayerHologram hologram = findByEntityId(targetEntityId);

        if (hologram != null) {
            hologram.mountForViewer(viewer);
        }
    }

    /**
     * The target entity was removed on the viewer's client: its passengers would stay floating in place,
     * so drop the hologram for this viewer. It is shown again on the next update if still visible.
     */
    public void handleEntitiesDestroyed(@NonNull Player viewer, @NonNull int[] entityIds) {
        for (int entityId : entityIds) {
            UUID targetId = targetIdByEntityId.get(entityId);
            if (targetId == null) {
                continue;
            }

            TrackedPlayerHologram hologram = hologramsByTarget.get(targetId);
            Set<UUID> viewers = viewersByTarget.get(targetId);

            if (hologram == null || viewers == null || !viewers.remove(viewer.getUniqueId())) {
                continue;
            }

            hologram.destroyForViewer(viewer);
        }
    }

    private TrackedPlayerHologram findByEntityId(int entityId) {
        UUID targetId = targetIdByEntityId.get(entityId);
        return targetId == null ? null : hologramsByTarget.get(targetId);
    }

    public void refreshCachedLines(@NonNull UUID targetId) {
        HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();

        if (config == null || !config.isEnabled()) {
            hologramLinesByTarget.remove(targetId);
            belowNameLineByTarget.remove(targetId);
            hideTarget(targetId);
            return;
        }

        Deque<Double> probabilities = Raveon.INSTANCE.getViolationManager().getLocalProbabilities(targetId);
        PlayerAnalysisSnapshot snapshot = Raveon.INSTANCE.getViolationManager().getAnalysisSnapshot(targetId);

        double lastProbability = snapshot.probability();
        double averageProbability = averageOf(probabilities, lastProbability);
        double buffer = snapshot.buffer();

        List<String> updatedLines = new ArrayList<>(config.getLines().size());
        for (String template : config.getLines()) {
            updatedLines.add(applyPlaceholders(template, lastProbability, averageProbability, buffer));
        }

        List<String> previousLines = hologramLinesByTarget.get(targetId);
        if (!updatedLines.equals(previousLines)) {
            hologramLinesByTarget.put(targetId, updatedLines);
        }

        String updatedBelowNameLine = applyPlaceholders(config.getBelowNameLine(), lastProbability, averageProbability, buffer);
        belowNameLineByTarget.put(targetId, updatedBelowNameLine);
    }

    private double averageOf(Deque<Double> probabilities, double fallback) {
        if (probabilities == null || probabilities.isEmpty()) {
            return fallback;
        }

        double sum = 0.0D;
        for (double probability : probabilities) {
            sum += probability;
        }

        return sum / probabilities.size();
    }

    private void updateVisibleHolograms() {
        HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();

        if (config == null || !config.isEnabled()) {
            clearAllHolograms();
            clearAllBelowName();
            hologramLinesByTarget.clear();
            belowNameLineByTarget.clear();
            return;
        }

        cleanupOfflineTargets();

        for (Player target : Bukkit.getOnlinePlayers()) {
            refreshCachedLines(target.getUniqueId());
            refreshTargetViewers(target, config);
        }
    }

    private void refreshTargetViewers(@NonNull Player target, @NonNull HologramConfigManager config) {
        UUID targetId = target.getUniqueId();

        Set<UUID> candidateViewers = new HashSet<>(enabledViewers);
        Set<UUID> existingHologramViewers = viewersByTarget.get(targetId);
        if (existingHologramViewers != null) {
            candidateViewers.addAll(existingHologramViewers);
        }
        Set<UUID> existingBelowNameViewers = belowNameViewersByTarget.get(targetId);
        if (existingBelowNameViewers != null) {
            candidateViewers.addAll(existingBelowNameViewers);
        }

        for (UUID viewerId : candidateViewers) {
            Player viewer = Bukkit.getPlayer(viewerId);

            if (!canViewerSeeTarget(viewer, target)) {
                stopHologramForViewer(targetId, viewer);
                stopBelowNameForViewer(targetId, viewer);
                continue;
            }

            boolean useBelowName = config.isBelowNameEnabled() && belowNameBridge.supportsBelowName(viewer);
            boolean useHologram = !useBelowName || config.isAlsoShowHologramWithBelowName();

            if (useBelowName) {
                pushBelowName(target, viewer);
            } else {
                stopBelowNameForViewer(targetId, viewer);
            }

            if (useHologram) {
                pushHologram(target, viewer, config);
            } else {
                stopHologramForViewer(targetId, viewer);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Floating hologram (entity-based)
    // ---------------------------------------------------------------------

    private void pushHologram(Player target, Player viewer, HologramConfigManager config) {
        UUID targetId = target.getUniqueId();
        List<String> lines = hologramLinesByTarget.get(targetId);

        if (lines == null || lines.isEmpty()) {
            stopHologramForViewer(targetId, viewer);
            return;
        }

        TrackedPlayerHologram hologram = hologramsByTarget.get(targetId);

        if (hologram != null && hologram.getTargetEntityId() != target.getEntityId()) {
            hideTarget(targetId);
            hologram = null;
        }

        if (hologram == null) {
            hologram = new TrackedPlayerHologram(target.getEntityId());
            hologramsByTarget.put(targetId, hologram);
            targetIdByEntityId.put(target.getEntityId(), targetId);
        }

        hologram.refreshTargetPassengers(target);

        Set<UUID> currentViewers = viewersByTarget.computeIfAbsent(targetId, ignored -> ConcurrentHashMap.newKeySet());
        currentViewers.add(viewer.getUniqueId());

        hologram.updateForViewer(viewer, target.getLocation(), lines, config.getOffset(), config.getLineSpacing());

        cleanupEmptyTarget(targetId, currentViewers);
    }

    private void stopHologramForViewer(UUID targetId, Player viewer) {
        Set<UUID> viewers = viewersByTarget.get(targetId);
        if (viewers == null || viewer == null || !viewers.remove(viewer.getUniqueId())) {
            return;
        }

        TrackedPlayerHologram hologram = hologramsByTarget.get(targetId);
        if (hologram != null && viewer.isOnline()) {
            hologram.destroyForViewer(viewer);
        }

        cleanupEmptyTarget(targetId, viewers);
    }

    // ---------------------------------------------------------------------
    // Below-name (packet scoreboard, 1.20.3+ only)
    // ---------------------------------------------------------------------

    private void pushBelowName(Player target, Player viewer) {
        UUID targetId = target.getUniqueId();
        String text = belowNameLineByTarget.get(targetId);

        if (text == null || text.isEmpty()) {
            stopBelowNameForViewer(targetId, viewer);
            return;
        }

        BelowNameViewerState state = belowNameStateByViewer.computeIfAbsent(
                viewer.getUniqueId(),
                id -> new BelowNameViewerState(objectiveNameFor(id))
        );

        if (!state.created) {
            belowNameBridge.createObjective(viewer, state.objectiveName);
            state.created = true;
        }

        String previous = state.lastTextByTarget.put(targetId, text);
        if (!text.equals(previous)) {
            belowNameBridge.updateEntry(viewer, state.objectiveName, target.getName(), text);
        }

        belowNameViewersByTarget.computeIfAbsent(targetId, ignored -> ConcurrentHashMap.newKeySet())
                .add(viewer.getUniqueId());
    }

    private void stopBelowNameForViewer(UUID targetId, Player viewer) {
        if (viewer == null) {
            return;
        }

        Set<UUID> viewers = belowNameViewersByTarget.get(targetId);
        if (viewers != null) {
            viewers.remove(viewer.getUniqueId());
        }

        BelowNameViewerState state = belowNameStateByViewer.get(viewer.getUniqueId());
        if (state == null) {
            return;
        }

        if (state.lastTextByTarget.remove(targetId) != null && state.created && viewer.isOnline()) {
            Player target = Bukkit.getPlayer(targetId);
            String entryName = target != null ? target.getName() : null;

            if (entryName != null) {
                belowNameBridge.removeEntry(viewer, state.objectiveName, entryName);
            }
        }
    }

    private void removeBelowNameViewer(@NonNull Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        BelowNameViewerState state = belowNameStateByViewer.remove(viewerId);

        for (Set<UUID> viewers : belowNameViewersByTarget.values()) {
            viewers.remove(viewerId);
        }

        if (state != null && state.created && viewer.isOnline()) {
            belowNameBridge.removeObjective(viewer, state.objectiveName);
        }
    }

    private void clearAllBelowName() {
        for (Map.Entry<UUID, BelowNameViewerState> entry : belowNameStateByViewer.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            BelowNameViewerState state = entry.getValue();

            if (viewer != null && viewer.isOnline() && state.created) {
                belowNameBridge.removeObjective(viewer, state.objectiveName);
            }
        }

        belowNameStateByViewer.clear();
        belowNameViewersByTarget.clear();
    }

    private String objectiveNameFor(UUID viewerId) {
        return "rvai" + Integer.toHexString(viewerId.hashCode());
    }

    /**
     * Per-viewer below-name state: the fake objective assigned to them, whether it has
     * been created on their client yet, and the last text sent per target (to skip resends).
     */
    private static final class BelowNameViewerState {
        private final String objectiveName;
        private boolean created;
        private final Map<UUID, String> lastTextByTarget = new ConcurrentHashMap<>();

        private BelowNameViewerState(String objectiveName) {
            this.objectiveName = objectiveName;
        }
    }

    // ---------------------------------------------------------------------
    // Shared cleanup / visibility helpers
    // ---------------------------------------------------------------------

    private void hideTarget(@NonNull UUID targetId) {
        Set<UUID> viewerIds = viewersByTarget.remove(targetId);
        TrackedPlayerHologram hologram = hologramsByTarget.remove(targetId);

        if (hologram != null) {
            targetIdByEntityId.remove(hologram.getTargetEntityId());
        }

        if (viewerIds != null && hologram != null) {
            for (UUID viewerId : viewerIds) {
                Player viewer = Bukkit.getPlayer(viewerId);

                if (viewer != null && viewer.isOnline()) {
                    hologram.destroyForViewer(viewer);
                }
            }
        }

        Set<UUID> belowNameViewerIds = belowNameViewersByTarget.remove(targetId);
        if (belowNameViewerIds != null) {
            for (UUID viewerId : belowNameViewerIds) {
                Player viewer = Bukkit.getPlayer(viewerId);
                BelowNameViewerState state = belowNameStateByViewer.get(viewerId);

                if (state == null || viewer == null || !viewer.isOnline()) {
                    continue;
                }

                if (state.lastTextByTarget.remove(targetId) != null && state.created) {
                    Player target = Bukkit.getPlayer(targetId);
                    String entryName = target != null ? target.getName() : null;

                    if (entryName != null) {
                        belowNameBridge.removeEntry(viewer, state.objectiveName, entryName);
                    }
                }
            }
        }
    }

    private void clearAllHolograms() {
        for (Map.Entry<UUID, Set<UUID>> entry : new ArrayList<>(viewersByTarget.entrySet())) {
            TrackedPlayerHologram hologram = hologramsByTarget.get(entry.getKey());
            if (hologram == null) {
                continue;
            }

            for (UUID viewerId : new HashSet<>(entry.getValue())) {
                Player viewer = Bukkit.getPlayer(viewerId);

                if (viewer != null && viewer.isOnline()) {
                    hologram.destroyForViewer(viewer);
                }
            }
        }

        viewersByTarget.clear();
        hologramsByTarget.clear();
        targetIdByEntityId.clear();
    }

    private void cleanupOfflineTargets() {
        Set<UUID> targetIds = new HashSet<>();
        targetIds.addAll(hologramsByTarget.keySet());
        targetIds.addAll(hologramLinesByTarget.keySet());
        targetIds.addAll(belowNameViewersByTarget.keySet());
        targetIds.addAll(belowNameLineByTarget.keySet());

        for (UUID targetId : targetIds) {
            Player target = Bukkit.getPlayer(targetId);

            if (target != null && target.isOnline()) {
                continue;
            }

            hideTarget(targetId);
            hologramLinesByTarget.remove(targetId);
            belowNameLineByTarget.remove(targetId);
        }
    }

    private void cleanupEmptyTarget(UUID targetId, Set<UUID> viewers) {
        if (!viewers.isEmpty()) {
            return;
        }

        viewersByTarget.remove(targetId);

        TrackedPlayerHologram hologram = hologramsByTarget.remove(targetId);
        if (hologram != null) {
            targetIdByEntityId.remove(hologram.getTargetEntityId());
        }
    }

    private boolean canViewerSeeTarget(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return false;
        }

        if (!viewer.isOnline() || !target.isOnline()) {
            return false;
        }

        if (!hasHologramsEnabled(viewer.getUniqueId())) {
            return false;
        }

        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            return false;
        }

        if (!viewer.getWorld().equals(target.getWorld())) {
            return false;
        }

        // Respect vanish plugins: a hologram must never reveal a player the viewer can't see.
        if (!viewer.canSee(target)) {
            return false;
        }

        return viewer.getLocation().distanceSquared(target.getLocation()) <= 900.0D;
    }

    private String applyPlaceholders(
            @NonNull String lineTemplate,
            double lastProbability,
            double averageProbability,
            double buffer
    ) {
        return lineTemplate
                .replace("{avg_color}", Raveon.INSTANCE.getMainConfigManager().getChanceColor(averageProbability))
                .replace("{prob}", formatProbability(lastProbability))
                .replace("{avg}", formatProbability(averageProbability))
                .replace("{buffer}", formatBuffer(buffer));
    }

    private String formatProbability(double probability) {
        return PROB_FORMAT.format(probability);
    }

    private String formatBuffer(double buffer) {
        return BUFFER_FORMAT.format(buffer);
    }
}