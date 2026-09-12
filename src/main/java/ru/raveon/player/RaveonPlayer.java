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
import ru.raveon.manager.anticheat.PunishmentManager;
import ru.raveon.manager.anticheat.CheckManager;
import ru.raveon.service.train.UploadService;
import ru.raveon.utils.math.RotationRingBuffer;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class RaveonPlayer {
    private final UUID uuid;
    private final String name;
    private final User user;
    private final CheckManager checkManager;
    private final PunishmentManager punishmentManager;
    private final RotationRingBuffer rotationBuffer;

    private Player bukkitPlayer;
    private List<RotationFrame> lastAnalyzedFrames = null;

    private long lastCombatTime = 0L;
    private boolean isInCombat = false;
    private UUID lastDamagedEntity;

    public RaveonPlayer(User user) {
        this.user = user;
        this.name = user.getName();
        this.uuid = user.getUUID();
        this.bukkitPlayer = Bukkit.getPlayer(uuid);

        this.checkManager = new CheckManager(this);
        this.punishmentManager = new PunishmentManager(Raveon.INSTANCE, Raveon.INSTANCE.getPunishmentConfigManager());

        int framesToAnalyze = Raveon.INSTANCE.getChecksConfigManager().getAnalysisSequence();
        this.rotationBuffer = new RotationRingBuffer(framesToAnalyze);
    }

    public void startDatasetsCollection(String name, boolean cheater) {
        Player target = getBukkitPlayer();
        if (target == null) {
            return;
        }

        TrainData trainData = getTrainData();
        if (trainData.isDatasetsCollecting()) {
            return;
        }

        String datasetName = name + "_" + UUID.randomUUID().toString().substring(0, 6);
        UploadService uploadService = new UploadService(
                Raveon.INSTANCE,
                datasetName,
                "datasets/" + this.name
        );

        trainData.setUploadService(uploadService);
        trainData.setDatasetsCollecting(true);
        trainData.setMarkedCheater(cheater);

        Player owner = trainData.getDatasetsOwner() == null ? null : Bukkit.getPlayer(trainData.getDatasetsOwner());

        Bukkit.getPluginManager().callEvent(new StartTrainEvent(
                target,
                trainData,
                owner,
                cheater,
                datasetName
        ));
    }

    public void writeFrame(RotationFrame rotationFrame) {
        TrainData trainData = getTrainData();
        if (trainData.isDatasetsCollecting()) {
            trainData.writeFrame(rotationFrame);
        }

        if (!isInCombat()) {
            return;
        }

        rotationBuffer.addFrame(rotationFrame);

        if (rotationBuffer.isFull()) {
            Raveon.INSTANCE.getAnalyzeService().analyzePlayerFrames(this);
        }
    }

    public void stopDatasetsCollection() {
        TrainData trainData = getTrainData();
        trainData.writeDatasetFrames();
        trainData.stopCollecting();
    }

    public void markAttack(UUID targetUuid) {
        this.lastDamagedEntity = targetUuid;
        this.checkManager.getAimAI().handleAttack(targetUuid);
        tagCombat();
    }

    public void tagCombat() {
        getTrainData().tagCombat();

        this.lastCombatTime = System.currentTimeMillis() + Raveon.INSTANCE.getChecksConfigManager().getCombatTimer();
        this.isInCombat = true;
    }

    public TrainData getTrainData() {
        return Raveon.INSTANCE.getPlayerDataManager().getOrCreateTrainData(uuid, name);
    }

    public UUID getLastDamagedEntity() {
        if (System.currentTimeMillis() > this.lastCombatTime) {
            this.lastDamagedEntity = null;
        }

        return lastDamagedEntity;
    }

    public boolean isInCombat() {
        boolean wasInCombat = this.isInCombat;

        if (System.currentTimeMillis() > this.lastCombatTime) {
            this.isInCombat = false;
        }

        if (wasInCombat && !this.isInCombat) {
            rotationBuffer.clear();
        }

        return this.isInCombat;
    }

    public Player getBukkitPlayer() {
        if (bukkitPlayer != null) {
            return bukkitPlayer;
        }

        return bukkitPlayer = Bukkit.getPlayer(uuid);
    }

    public void reload() {
        checkManager.reload();
        punishmentManager.reload();
    }
}
