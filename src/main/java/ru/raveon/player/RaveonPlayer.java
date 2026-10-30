package ru.raveon.player;

import com.github.retrooper.packetevents.protocol.player.User;

import lombok.Getter;
import lombok.Setter;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import ru.raveon.Raveon;
import ru.raveon.api.events.StartTrainEvent;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.api.models.data.TrainData;
import ru.raveon.checks.data.UltraData;
import ru.raveon.manager.anticheat.CheckManager;
import ru.raveon.model.ModelId;
import ru.raveon.service.train.UploadService;
import ru.raveon.utils.math.RotationRingBuffer;

import java.util.UUID;

@Getter
@Setter
public class RaveonPlayer {
    private final UUID uuid;
    private final String name;
    private final User user;
    private final CheckManager checkManager;

    private volatile RotationRingBuffer<RotationFrame> rotationBuffer;
    private final UltraData ultraData = new UltraData();

    private Player bukkitPlayer;

    private volatile long lastCombatTime = 0L;
    private volatile boolean isInCombat = false;

    public RaveonPlayer(User user) {
        this.user = user;
        this.name = user.getName();
        this.uuid = user.getUUID();
        this.bukkitPlayer = Bukkit.getPlayer(uuid);

        this.checkManager = new CheckManager(this);
        int framesToAnalyze = Raveon.INSTANCE.getChecksConfigManager().getAnalysisSequence();
        this.rotationBuffer = new RotationRingBuffer<>(framesToAnalyze);
    }

    public boolean startDatasetsCollection(String name, boolean cheater, ModelId model, UUID ownerUuid) {
        Player target = getBukkitPlayer();
        if (target == null) {
            return false;
        }

        TrainData trainData = getTrainData(model);
        if (trainData.isDatasetsCollecting()) {
            return false;
        }

        String datasetName = name + "_" + UUID.randomUUID().toString().substring(0, 6);
        UploadService uploadService =
                new UploadService(
                        Raveon.INSTANCE,
                        datasetName,
                        "datasets/"
                                + model.key()
                                + "/"
                                + (cheater ? "cheats" : "legits")
                                + "/"
                                + this.name,
                        model);

        if (!trainData.begin(uploadService, cheater, ownerUuid)) return false;

        Player owner =
                trainData.getDatasetsOwner() == null
                        ? null
                        : Bukkit.getPlayer(trainData.getDatasetsOwner());

        Bukkit.getPluginManager()
                .callEvent(new StartTrainEvent(target, trainData, owner, cheater, datasetName));
        return true;
    }

    public void writeFrame(RotationFrame rotationFrame) {
        TrainData trainData = getTrainData();
        if (trainData.isDatasetsCollecting()) {
            trainData.writeFrame(rotationFrame);
        }

        if (!checkManager.getAimAI().isEnabled() || !isInCombat()) {
            return;
        }

        rotationBuffer.addFrame(rotationFrame);

        if (rotationBuffer.isFull()) {
            Raveon.INSTANCE.getAnalyzeService().analyzePlayerFrames(this);
        }
    }

    public void markAttack(UUID targetUuid) {
        tagCombat();
    }

    public void tagCombat() {
        getTrainData().tagCombat();

        long now = System.currentTimeMillis();
        if (!this.isInCombat || now > this.lastCombatTime) resetCombatWindow();
        this.lastCombatTime = now + Raveon.INSTANCE.getChecksConfigManager().getCombatTimer();
        this.isInCombat = true;
    }

    public TrainData getTrainData(ModelId model) {
        return Raveon.INSTANCE.getPlayerDataManager().getOrCreateTrainData(uuid, name, model);
    }

    public TrainData getTrainData() {
        return Raveon.INSTANCE.getPlayerDataManager().getOrCreateTrainData(uuid, name);
    }

    public boolean isInCombat() {
        if (this.isInCombat && System.currentTimeMillis() > this.lastCombatTime) {
            this.isInCombat = false;
            resetCombatWindow();
        }
        return this.isInCombat;
    }

    private void resetCombatWindow() {
        checkManager.invalidate(ModelId.PRO);
        checkManager.getAimAI().resetRotation();
    }

    public Player getBukkitPlayer() {
        if (bukkitPlayer != null) {
            return bukkitPlayer;
        }

        return bukkitPlayer = Bukkit.getPlayer(uuid);
    }

    public void resizeRotationBuffer(int capacity) {
        if (rotationBuffer == null || rotationBuffer.capacity() != capacity)
            rotationBuffer = new RotationRingBuffer<>(capacity);
    }

    public void reload() {
        checkManager.reload();
    }
}
