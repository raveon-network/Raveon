package ru.raveon.checks.impl.ai;

import lombok.Getter;

import org.bukkit.entity.Player;

import ru.raveon.Raveon;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.checks.Check;
import ru.raveon.checks.CheckData;
import ru.raveon.checks.type.PacketCheck;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.model.ModelId;
import ru.raveon.manager.anticheat.ModelDetectionState;
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.ProbabilityUtils;
import ru.raveon.utils.math.MouseCalculator;

@Getter
@CheckData(name = "AimAI", configName = "analyze")
public final class AimAI extends Check implements PacketCheck {
    private final ModelId model;
    private volatile ChecksConfigManager settings;

    @Override
    public String getCheckName() {
        return model == ModelId.PRO ? super.getCheckName() : super.getCheckName() + "-Ultra";
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && settings != null && settings.isModelEnabled(model);
    }

    private boolean rotationInitialized;
    private float lastYaw = 0.0F;
    private float lastPitch = 0.0F;
    private float lastDeltaYaw = 0.0F;
    private float lastDeltaPitch = 0.0F;
    private float lastAccelYaw = 0.0F;
    private float lastAccelPitch = 0.0F;
    private double lastProbability;

    public AimAI(RaveonPlayer player) {
        this(player, ModelId.PRO);
    }

    public AimAI(RaveonPlayer player, ModelId model) {
        super(player);
        this.model = model;
    }

    public void handleRotation(Player bukkitPlayer, float yaw, float pitch) {
        if (isEnabled() || player.getTrainData().isDatasetsCollecting()) {
            processRotation(bukkitPlayer, normalizeYaw(yaw), clampPitch(pitch));
        }
    }

    private void processRotation(Player bukkitPlayer, float currentYaw, float currentPitch) {
        if (!bukkitPlayer.isOnline()) {
            return;
        }

        if (!rotationInitialized) {
            lastYaw = currentYaw;
            lastPitch = currentPitch;
            rotationInitialized = true;
            return;
        }

        if (settings.isAimAiBypassedInRegion(bukkitPlayer)) {
            updateRotationState(currentYaw, currentPitch);
            player.getRotationBuffer().clear();
            return;
        }

        float deltaYaw = getSignedAngleDelta(currentYaw, lastYaw);
        float deltaPitch = currentPitch - lastPitch;

        float accelYaw = MouseCalculator.calculateAcceleration(deltaYaw, lastDeltaYaw);
        float accelPitch = MouseCalculator.calculateAcceleration(deltaPitch, lastDeltaPitch);

        float jerkYaw = MouseCalculator.calculateJerk(accelYaw, lastAccelYaw);
        float jerkPitch = MouseCalculator.calculateJerk(accelPitch, lastAccelPitch);

        float gcdErrorYaw = MouseCalculator.calculateGCDError(deltaYaw, lastDeltaYaw);
        float gcdErrorPitch = MouseCalculator.calculateGCDError(deltaPitch, lastDeltaPitch);

        RotationFrame rotationFrame =
                new RotationFrame(
                        deltaYaw,
                        deltaPitch,
                        accelYaw,
                        accelPitch,
                        jerkYaw,
                        jerkPitch,
                        gcdErrorYaw,
                        gcdErrorPitch);

        lastYaw = currentYaw;
        lastPitch = currentPitch;
        lastDeltaYaw = deltaYaw;
        lastDeltaPitch = deltaPitch;
        lastAccelYaw = accelYaw;
        lastAccelPitch = accelPitch;

        player.writeFrame(rotationFrame);
    }

    public void handleAnalyzeResult(double chance) {
        Player bukkitPlayer = player.getBukkitPlayer();
        if (settings != Raveon.INSTANCE.getChecksConfigManager()
                || !isEnabled()
                || bukkitPlayer == null
                || !bukkitPlayer.isOnline()
                || !Double.isFinite(chance)) {
            return;
        }

        if (settings.isAimAiBypassedInRegion(bukkitPlayer)) {
            player.getRotationBuffer().clear();
            return;
        }

        lastProbability = Math.max(0.0D, Math.min(1.0D, chance));

        Raveon.INSTANCE
                .getViolationManager()
                .addAiProbability(
                        bukkitPlayer.getUniqueId(), lastProbability, true, model);

        ModelDetectionState.Result result =
                player.getCheckManager().getDetectionState().accept(model, lastProbability);
        if (result == null) return;
        for (ModelId current : ModelId.values()) {
            AimAI check = player.getCheckManager().getAimAI(current);
            Raveon.INSTANCE.getViolationManager().updateAnalysisSnapshot(
                    player.getUuid(), check.lastProbability, check.getBuffer(), current);
        }
        Raveon.INSTANCE.getMonitorManager().publish(bukkitPlayer, lastProbability, getBuffer(), model);
        Raveon.INSTANCE.getHologramManager().handleAnalysisUpdate(player.getUuid());
        if (result.crossed()) handleFlag(result);
    }

    private void handleFlag(ModelDetectionState.Result result) {
        if (!canFlag() || !isEnabled()) return;
        registerViolation();
        String verbose = "model: %s, buffer_model: %s, prob: %s%%, buffer: %.2f".formatted(
                result.model().key(), result.bufferOwner().key(),
                ProbabilityUtils.formatPercent(result.probability()), result.atResult());
        Raveon.INSTANCE.getPunishmentManager().handleFlag(this, result, verbose);
    }

    public double getBuffer() {
        return player.getCheckManager().getDetectionState().buffer(model);
    }

    @Override
    public boolean alert() {
        if (!canAlert() || !isEnabled() || !settings.getModelSettings().get(model).actions().alert()) return false;
        Player bukkitPlayer = player.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) return false;
        Raveon.INSTANCE.getPunishmentManager().executeAlert(this,
                "prob: %s%%, buffer: %.2f".formatted(
                        ProbabilityUtils.formatPercent(lastProbability), getBuffer()));
        return true;
    }

    @Override
    public boolean alert(String verbose) {
        return alert();
    }

    private void updateRotationState(float currentYaw, float currentPitch) {
        float deltaYaw = getSignedAngleDelta(currentYaw, lastYaw);
        float deltaPitch = currentPitch - lastPitch;

        float accelYaw = MouseCalculator.calculateAcceleration(deltaYaw, lastDeltaYaw);
        float accelPitch = MouseCalculator.calculateAcceleration(deltaPitch, lastDeltaPitch);

        lastYaw = currentYaw;
        lastPitch = currentPitch;
        lastDeltaYaw = deltaYaw;
        lastDeltaPitch = deltaPitch;
        lastAccelYaw = accelYaw;
        lastAccelPitch = accelPitch;
    }

    private float normalizeYaw(float yaw) {
        return MouseCalculator.normalizeAngle(yaw);
    }

    private float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private float getSignedAngleDelta(float current, float previous) {
        return MouseCalculator.normalizeAngle(current - previous);
    }

    public void resetRotation() {
        rotationInitialized = false;
        lastYaw = 0.0F;
        lastPitch = 0.0F;
        lastDeltaYaw = 0.0F;
        lastDeltaPitch = 0.0F;
        lastAccelYaw = 0.0F;
        lastAccelPitch = 0.0F;
    }

    public void reloadFrom(ChecksConfigManager settings) {
        this.settings = settings;
        super.reload(settings.getChecksConfig());
    }

    @Override
    public void reload() {
        reloadFrom(Raveon.INSTANCE.getChecksConfigManager());
    }

}
