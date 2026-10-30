package ru.raveon.api.models.data;

import lombok.Data;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import ru.raveon.Raveon;
import ru.raveon.api.events.EndTrainEvent;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.api.models.TrainStatisticInfoType;
import ru.raveon.api.models.UltraFrame;
import ru.raveon.model.ModelId;
import ru.raveon.service.train.UploadService;
import ru.raveon.utils.SchedulerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Data
public class TrainData {
    private final List<RotationFrame> datasetFrames;
    private final ModelId model;
    private final List<UltraFrame> ultraFrames = new ArrayList<>();
    private final Object lock = new Object();

    private final UUID playerUuid;
    private final String playerName;
    private volatile UploadService uploadService;
    private long sessionGeneration;
    private boolean saving;
    private int savingFrames;
    private boolean retired;
    private boolean stopNotified;
    private long lastStatisticsAt;
    private CompletableFuture<Boolean> saveFuture = CompletableFuture.completedFuture(true);
    private CompletableFuture<Boolean> completion = CompletableFuture.completedFuture(true);

    private volatile boolean isDatasetsCollecting = false;
    private volatile boolean isMarkedCheater = false;

    private int totalFramesCollected = 0;
    private int datasetsUploaded = 0;

    private long lastCombatTime = 0L;
    private boolean isInCombat = false;

    private UUID datasetsOwner;
    private BossBar trainBar;

    public TrainData(UUID playerUuid, String playerName) {
        this(playerUuid, playerName, ModelId.PRO);
    }

    public TrainData(UUID playerUuid, String playerName, ModelId model) {
        this.model = model;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.datasetFrames =
                new ArrayList<>(
                        Raveon.INSTANCE.getDataCollectConfigManager().getFramesToCollect(model));
    }

    public boolean begin(UploadService service, boolean cheater, UUID ownerUuid) {
        synchronized (lock) {
            if (!service.getPlugin().isEnabled() || retired || isDatasetsCollecting || saving
                    || !datasetFrames.isEmpty() || !ultraFrames.isEmpty()) return false;
            sessionGeneration++;
            uploadService = service;
            datasetsOwner = ownerUuid;
            isMarkedCheater = cheater;
            totalFramesCollected = 0;
            datasetsUploaded = 0;
            stopNotified = false;
            completion = new CompletableFuture<>();
            isDatasetsCollecting = true;
            return true;
        }
    }

    public boolean retireIfSaved() {
        synchronized (lock) {
            if (saving || isDatasetsCollecting || !datasetFrames.isEmpty() || !ultraFrames.isEmpty()) return false;
            retired = true;
            return true;
        }
    }

    public boolean hasPendingFrames() {
        synchronized (lock) {
            return saving || !datasetFrames.isEmpty() || !ultraFrames.isEmpty();
        }
    }

    public void writeFrame(RotationFrame rotationFrame) {
        if (!isInCombat()) {
            return;
        }

        appendFrame(rotationFrame, datasetFrames);
    }

    private <T> void appendFrame(T frame, List<T> frames) {
        boolean flush;
        boolean overflow;
        synchronized (lock) {
            if (!isDatasetsCollecting) return;
            int limit = Raveon.INSTANCE.getDataCollectConfigManager().getFramesToCollect(model);
            overflow = frames.size() >= limit * 2;
            if (overflow) isDatasetsCollecting = false;
            else frames.add(frame);
            flush = frames.size() >= limit && !saving;
        }
        if (overflow) {
            Raveon.INSTANCE.getLogger().warning("Dataset collection paused: writer is behind for " + playerName);
            stopCollecting();
        } else if (flush) writeDatasetFrames();
        else updateStatistics();
    }

    private void updateStatistics() {
        UUID owner;
        long generation;
        synchronized (lock) {
            long now = System.nanoTime();
            if (now - lastStatisticsAt < 500_000_000L || !isDatasetsCollecting) return;
            lastStatisticsAt = now;
            generation = sessionGeneration;
            owner = datasetsOwner;
        }
        if (owner == null || !Raveon.INSTANCE.isEnabled()) return;
        Player viewer = Bukkit.getPlayer(owner);
        if (viewer != null) SchedulerUtils.runEntity(Raveon.INSTANCE, viewer, () -> {
            synchronized (lock) {
                if (generation == sessionGeneration && isDatasetsCollecting) sendTrainStatistic(viewer);
            }
        });
    }

    private void sendTrainStatistic(Player owner) {
        if (Raveon.INSTANCE.getDataCollectConfigManager().getInfoType()
                == TrainStatisticInfoType.ACTIONBAR) {
            owner.sendActionBar(
                    LegacyComponentSerializer.legacySection().deserialize(generateInfoMessage()));
            return;
        }

        if (trainBar == null) {
            trainBar =
                    Bukkit.createBossBar(
                            generateInfoMessage(),
                            Raveon.INSTANCE.getDataCollectConfigManager().isAutosaveStatus()
                                    ? BarColor.RED
                                    : BarColor.GREEN,
                            BarStyle.SEGMENTED_20);
            if (Raveon.INSTANCE.getDataCollectConfigManager().isAutosaveStatus()) {
                trainBar.setProgress(0);
            }
        }

        if (!trainBar.getPlayers().contains(owner)) {
            trainBar.addPlayer(owner);
        }

        trainBar.setTitle(generateInfoMessage());
        if (!Raveon.INSTANCE.getDataCollectConfigManager().isAutosaveStatus()) {
            return;
        }

        if (datasetsUploaded <= 0) {
            trainBar.setProgress(0);
            trainBar.setColor(BarColor.RED);
            return;
        }

        double progress =
                Math.min(
                        1.0,
                        (double) datasetsUploaded
                                / Raveon.INSTANCE.getDataCollectConfigManager().getAutosaveValue());
        trainBar.setProgress(progress);

        if (progress >= 0.8) {
            trainBar.setColor(BarColor.GREEN);
        } else if (progress >= 0.4) {
            trainBar.setColor(BarColor.YELLOW);
        } else {
            trainBar.setColor(BarColor.RED);
        }
    }

    private @NotNull String generateInfoMessage() {
        int currentSize;
        synchronized (lock) {
            currentSize = model == ModelId.PRO ? datasetFrames.size() : ultraFrames.size();
        }
        return Raveon.INSTANCE
                .getLanguageService().text("datacollect.statistics")
                .replace("{model}", model.displayName())
                .replace("{player}", playerName)
                .replace("{frames_collecting}", String.valueOf(totalFramesCollected))
                .replace("{frames_size}", String.valueOf(currentSize))
                .replace("{sending}", String.valueOf(datasetsUploaded));
    }

    public void writeUltraFrame(UltraFrame frame) {
        if (model != ModelId.ULTRA || !isDatasetsCollecting || !isInCombat()) return;
        appendFrame(frame, ultraFrames);
    }

    public CompletableFuture<Boolean> writeDatasetFrames() {
        synchronized (lock) {
            if (saving) return saveFuture;
            int count = model == ModelId.PRO ? datasetFrames.size() : ultraFrames.size();
            if (count == 0) {
                if (!isDatasetsCollecting) finishSession();
                return CompletableFuture.completedFuture(true);
            }
            if (uploadService == null) return CompletableFuture.completedFuture(false);
            int length = Math.min(count, Raveon.INSTANCE.getDataCollectConfigManager().getFramesToCollect(model));
            long generation = sessionGeneration;
            UploadService service = uploadService;
            List<RotationFrame> proCopy = model == ModelId.PRO ? List.copyOf(datasetFrames.subList(0, length)) : List.of();
            List<UltraFrame> ultraCopy = model == ModelId.ULTRA ? List.copyOf(ultraFrames.subList(0, length)) : List.of();
            saving = true;
            savingFrames = length;
            saveFuture = model == ModelId.PRO
                    ? service.uploadPlayerData(proCopy, isMarkedCheater)
                    : service.uploadUltraPlayerData(ultraCopy, isMarkedCheater);
            CompletableFuture<Boolean> submitted = saveFuture;
            submitted.whenComplete((success, error) -> saved(generation, service, length, success, error));
            return submitted;
        }
    }

    private void saved(long generation, UploadService service, int count, Boolean success, Throwable error) {
        boolean flush;
        synchronized (lock) {
            if (generation != sessionGeneration || service != uploadService) return;
            saving = false;
            savingFrames = 0;
            if (error != null || !Boolean.TRUE.equals(success)) {
                isDatasetsCollecting = false;
                completion.complete(false);
                Raveon.INSTANCE.getLogger().warning("Dataset save failed; unsaved frames retained for "
                        + playerName + " / " + model.displayName() + ": "
                        + (model == ModelId.PRO ? datasetFrames.size() : ultraFrames.size())
                        + " frames, " + service.getDatasetFile());
                hideStatistics();
                return;
            }
            if (model == ModelId.PRO) datasetFrames.subList(0, count).clear();
            else ultraFrames.subList(0, count).clear();
            totalFramesCollected += count;
            datasetsUploaded++;
            var config = Raveon.INSTANCE.getDataCollectConfigManager();
            if (config.isAutosaveStatus() && datasetsUploaded >= config.getAutosaveValue())
                isDatasetsCollecting = false;
            int remaining = model == ModelId.PRO ? datasetFrames.size() : ultraFrames.size();
            flush = remaining > 0 && (!isDatasetsCollecting || remaining >= config.getFramesToCollect(model));
            if (!isDatasetsCollecting && remaining == 0) finishSession();
        }
        if (flush) writeDatasetFrames();
        else updateStatistics();
    }

    public CompletableFuture<Boolean> stopCollecting() {
        synchronized (lock) {
            isDatasetsCollecting = false;
            if (uploadService == null) return CompletableFuture.completedFuture(true);
            if (completion.isDone() && hasPendingFrames()) completion = new CompletableFuture<>();
            hideStatistics();
            writeDatasetFrames();
            return completion;
        }
    }

    private void hideStatistics() {
        BossBar bar = trainBar;
        trainBar = null;
        if (bar == null) return;
        try {
            bar.setVisible(false);
            if (!Raveon.INSTANCE.isEnabled()) return;
            Player owner = datasetsOwner == null ? null : Bukkit.getPlayer(datasetsOwner);
            if (owner != null) SchedulerUtils.runEntity(Raveon.INSTANCE, owner, bar::removeAll);
        } catch (RuntimeException error) {
            Raveon.INSTANCE.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not hide dataset statistics", error);
        }
    }

    private void finishSession() {
        if (stopNotified || uploadService == null) return;
        stopNotified = true;
        hideStatistics();
        int collected = totalFramesCollected;
        int uploaded = datasetsUploaded;
        boolean cheater = isMarkedCheater;
        long generation = sessionGeneration;
        completion.complete(true);
        if (!Raveon.INSTANCE.isEnabled()) return;
        Player target = Bukkit.getPlayer(playerUuid);
        Player owner = datasetsOwner == null ? null : Bukkit.getPlayer(datasetsOwner);
        if (owner != null) SchedulerUtils.runEntity(Raveon.INSTANCE, owner, () -> {
            synchronized (lock) {
                if (sessionGeneration != generation) return;
            }
            owner.sendMessage(Raveon.INSTANCE.getLanguageService().text("datacollect.stop.auto")
                    .replace("{target}", playerName));
        });
        if (target != null) SchedulerUtils.runEntity(Raveon.INSTANCE, target, () -> {
            synchronized (lock) {
                if (sessionGeneration != generation) return;
            }
            new EndTrainEvent(target, this, owner, cheater, collected, uploaded).callEvent();
        });
    }

    public void tagCombat() {
        synchronized (lock) {
            isInCombat();
            this.lastCombatTime = System.currentTimeMillis()
                    + Raveon.INSTANCE.getDataCollectConfigManager().getCombatTimer(model);
            this.isInCombat = true;
        }
    }

    public boolean isInCombat() {
        synchronized (lock) {
            if (isInCombat && System.currentTimeMillis() > lastCombatTime) {
                isInCombat = false;
                if (isDatasetsCollecting
                        && Raveon.INSTANCE.getDataCollectConfigManager().isClearBufferOnCombatTimeout()) {
                    if (model == ModelId.PRO)
                        datasetFrames.subList(savingFrames, datasetFrames.size()).clear();
                    else ultraFrames.subList(savingFrames, ultraFrames.size()).clear();
                }
            }
            return isInCombat;
        }
    }
}
