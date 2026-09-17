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
import java.util.Collections;
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
    private final Map<UUID, TrackedPlayerHologram> hologramsByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersByTarget = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> targetIdByEntityId = new ConcurrentHashMap<>();
    private final Set<UUID> enabledViewers = ConcurrentHashMap.newKeySet();

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
        hologramLinesByTarget.clear();
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
    }

    public void handleQuit(@NonNull UUID targetId) {
        hideTarget(targetId);
        hologramLinesByTarget.remove(targetId);
    }

    public void handleWorldChange(@NonNull Player player) {
        removeViewer(player);
        hideTarget(player.getUniqueId());
    }

    /**
     * Only needed for legacy armor stand holograms; display holograms ride the target and follow it client-side.
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
            hideTarget(targetId);
            return;
        }

        List<String> updatedLines = buildLinesForTarget(targetId, config);
        List<String> previousLines = hologramLinesByTarget.get(targetId);

        if (updatedLines.equals(previousLines)) {
            return;
        }

        hologramLinesByTarget.put(targetId, updatedLines);
    }

    private void updateVisibleHolograms() {
        HologramConfigManager config = Raveon.INSTANCE.getHologramConfigManager();

        if (config == null || !config.isEnabled()) {
            clearAllHolograms();
            hologramLinesByTarget.clear();
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
        List<String> lines = hologramLinesByTarget.get(targetId);

        if (lines == null || lines.isEmpty()) {
            hideTarget(targetId);
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

        Set<UUID> currentViewers = viewersByTarget.computeIfAbsent(
                targetId,
                ignored -> ConcurrentHashMap.newKeySet()
        );

        Location targetLocation = target.getLocation();
        double offset = config.getOffset();
        double spacing = config.getLineSpacing();

        for (UUID viewerId : new HashSet<>(currentViewers)) {
            Player viewer = Bukkit.getPlayer(viewerId);

            if (!canViewerSeeTarget(viewer, target)) {
                if (viewer != null && viewer.isOnline()) {
                    hologram.destroyForViewer(viewer);
                }

                currentViewers.remove(viewerId);
                continue;
            }

            hologram.updateForViewer(viewer, targetLocation, lines, offset, spacing);
        }

        for (UUID viewerId : enabledViewers) {
            if (currentViewers.contains(viewerId)) {
                continue;
            }

            Player viewer = Bukkit.getPlayer(viewerId);
            if (!canViewerSeeTarget(viewer, target)) {
                continue;
            }

            currentViewers.add(viewerId);
            hologram.updateForViewer(viewer, targetLocation, lines, offset, spacing);
        }

        cleanupEmptyTarget(targetId, currentViewers);
    }

    private void hideTarget(@NonNull UUID targetId) {
        Set<UUID> viewerIds = viewersByTarget.remove(targetId);
        TrackedPlayerHologram hologram = hologramsByTarget.remove(targetId);

        if (hologram != null) {
            targetIdByEntityId.remove(hologram.getTargetEntityId());
        }

        if (viewerIds == null || hologram == null) {
            return;
        }

        for (UUID viewerId : viewerIds) {
            Player viewer = Bukkit.getPlayer(viewerId);

            if (viewer != null && viewer.isOnline()) {
                hologram.destroyForViewer(viewer);
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

        for (UUID targetId : targetIds) {
            Player target = Bukkit.getPlayer(targetId);

            if (target != null && target.isOnline()) {
                continue;
            }

            hideTarget(targetId);
            hologramLinesByTarget.remove(targetId);
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

        return viewer.getLocation().distanceSquared(target.getLocation()) <= 900.0D;
    }

    private List<String> buildLinesForTarget(
            @NonNull UUID targetId,
            @NonNull HologramConfigManager config
    ) {
        Deque<Double> probabilities = Raveon.INSTANCE
                .getViolationManager()
                .getLocalProbabilities(targetId);

        PlayerAnalysisSnapshot snapshot = Raveon.INSTANCE
                .getViolationManager()
                .getAnalysisSnapshot(targetId);

        if (probabilities == null || probabilities.isEmpty()) {
            return buildFormattedLines(
                    config,
                    List.of(),
                    0.0D,
                    snapshot.buffer()
            );
        }

        List<Double> history = new ArrayList<>(probabilities);
        Collections.reverse(history);

        double sum = 0.0D;
        for (double probability : history) {
            sum += probability;
        }

        double averageProbability = sum / history.size();

        return buildFormattedLines(
                config,
                history,
                averageProbability,
                snapshot.buffer()
        );
    }

    private List<String> buildFormattedLines(
            @NonNull HologramConfigManager config,
            @NonNull List<Double> history,
            double averageProbability,
            double buffer) {
        String formattedHistory = buildHistoryText(config, history);
        List<String> result = new ArrayList<>();

        for (String template : config.getLines()) {
            if (!template.contains("{history}")) {
                result.add(applyPlaceholders(template, formattedHistory, averageProbability, buffer));
                continue;
            }

            String[] historyLines = formattedHistory.split("\n", -1);
            for (String historyLine : historyLines) {
                result.add(applyPlaceholders(template, historyLine, averageProbability, buffer));
            }
        }

        return result;
    }

    private String buildHistoryText(@NonNull HologramConfigManager config, @NonNull List<Double> history) {
        int linesCount = Math.max(1, config.getHistoryLines());
        int valuesPerLine = Math.max(1, config.getHistoryProbsPerLine());
        int maxValues = linesCount * valuesPerLine;

        if (history.isEmpty()) {
            return "\n".repeat(Math.max(0, linesCount - 1));
        }

        int limit = Math.min(history.size(), maxValues);
        StringBuilder result = new StringBuilder(limit * 12);

        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                result.append(index % valuesPerLine == 0 ? '\n' : ' ');
            }

            result.append(formatProbabilityWithColor(history.get(index)));
        }

        return result.toString();
    }

    private String applyPlaceholders(
            @NonNull String lineTemplate,
            @NonNull String historyText,
            double averageProbability,
            double buffer
    ) {
        return lineTemplate
                .replace("{history}", historyText)
                .replace("{avg_color}", Raveon.INSTANCE.getMainConfigManager().getChanceColor(averageProbability))
                .replace("{avg}", formatProbability(averageProbability))
                .replace("{buffer}", formatBuffer(buffer));
    }

    private String formatProbabilityWithColor(double probability) {
        return Raveon.INSTANCE.getMainConfigManager().getChanceColor(probability)
                + formatProbability(probability);
    }

    private String formatProbability(double probability) {
        return PROB_FORMAT.format(probability);
    }
    private String formatBuffer(double buffer) {
        return BUFFER_FORMAT.format(buffer);
    }
}
