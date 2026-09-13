package ru.raveon.manager.analytic.hologram;

import lombok.Getter;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
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

    public void handleMovement(@NonNull Player target) {
        if (!target.isOnline()) {
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

        Location baseLocation = createBaseLocation(target);
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

            hologram.teleportForViewer(viewer, baseLocation, spacing);
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

        TrackedPlayerHologram hologram = hologramsByTarget.computeIfAbsent(
                targetId,
                ignored -> new TrackedPlayerHologram()
        );

        Set<UUID> currentViewers = viewersByTarget.computeIfAbsent(
                targetId,
                ignored -> ConcurrentHashMap.newKeySet()
        );

        Location baseLocation = createBaseLocation(target);
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

            hologram.updateForViewer(viewer, baseLocation, lines, spacing);
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
            hologram.updateForViewer(viewer, baseLocation, lines, spacing);
        }

        cleanupEmptyTarget(targetId, currentViewers);
    }

    private void hideTarget(@NonNull UUID targetId) {
        Set<UUID> viewerIds = viewersByTarget.remove(targetId);
        TrackedPlayerHologram hologram = hologramsByTarget.remove(targetId);

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
        hologramsByTarget.remove(targetId);
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

    private Location createBaseLocation(@NonNull Player target) {
        return target.getLocation()
                .clone()
                .add(0.0D, Raveon.INSTANCE.getHologramConfigManager().getOffset(), 0.0D);
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
