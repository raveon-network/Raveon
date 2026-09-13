package ru.raveon.checks.impl.ai;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ru.raveon.Raveon;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.checks.Check;
import ru.raveon.checks.CheckData;
import ru.raveon.checks.type.PacketCheck;
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.math.MouseCalculator;
import ru.raveon.utils.SchedulerUtils;

import java.util.UUID;

@Setter
@Getter
@CheckData(name = "AimAI", configName = "analyze")
public final class AimAI extends Check implements PacketCheck {
    private static final double CHEAT_PROBABILITY = 0.90D;
    private static final double LEGIT_PROBABILITY = 0.10D;

    private double buffer = 0.0D;
    private double bufferFlagThreshold = 50.0D;
    private double bufferResetOnFlag = 25.0D;
    private double bufferMultiplier = 100.0D;
    private double bufferDecrease = 0.25D;

    private float lastYaw = 0.0F;
    private float lastPitch = 0.0F;
    private float lastDeltaYaw = 0.0F;
    private float lastDeltaPitch = 0.0F;
    private float lastAccelYaw = 0.0F;
    private float lastAccelPitch = 0.0F;
    private float lastYawToTargetDiff = 0.0F;
    private float lastPitchToTargetDiff = 0.0F;

    private long rotationSampleIndex = 0L;
    private long lastAttackSampleIndex = -1L;

    private double lastProbability;

    public AimAI(RaveonPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (event.getConnectionState() != ConnectionState.PLAY) {
            return;
        }

        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            return;
        }

        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
        if (!flying.hasRotationChanged()) {
            return;
        }

        Player bukkitPlayer = event.getPlayer();
        if (bukkitPlayer == null) {
            return;
        }

        float currentYaw = normalizeYaw(flying.getLocation().getYaw());
        float currentPitch = clampPitch(flying.getLocation().getPitch());
        SchedulerUtils.runEntity(
                Raveon.INSTANCE,
                bukkitPlayer,
                () -> processRotation(bukkitPlayer, currentYaw, currentPitch)
        );
    }

    private void processRotation(Player bukkitPlayer, float currentYaw, float currentPitch) {
        if (!bukkitPlayer.isOnline()) {
            return;
        }

        if (Raveon.INSTANCE.getChecksConfigManager().isAimAiBypassedInRegion(bukkitPlayer)) {
            updateRotationState(currentYaw, currentPitch);
            player.getRotationBuffer().clear();
            return;
        }

        Entity target = Raveon.INSTANCE.getTargetEntityIndex().getByUniqueId(player.getLastDamagedEntity());
        if (target == null || !target.isValid()) {
            updateRotationState(currentYaw, currentPitch);
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

        float yawToTargetDiff = calculateSignedYawToTargetDiff(bukkitPlayer, target);
        float pitchToTargetDiff = calculateSignedPitchToTargetDiff(bukkitPlayer, target);

        rotationSampleIndex++;

        RotationFrame rotationFrame = new RotationFrame(
                deltaYaw,
                deltaPitch,
                accelYaw,
                accelPitch,
                jerkYaw,
                jerkPitch,
                gcdErrorYaw,
                gcdErrorPitch
        );

        lastYaw = currentYaw;
        lastPitch = currentPitch;
        lastDeltaYaw = deltaYaw;
        lastDeltaPitch = deltaPitch;
        lastAccelYaw = accelYaw;
        lastAccelPitch = accelPitch;
        lastYawToTargetDiff = yawToTargetDiff;
        lastPitchToTargetDiff = pitchToTargetDiff;

        player.writeFrame(rotationFrame);
    }

    public void handleAttack(UUID targetUuid) {
        player.setLastDamagedEntity(targetUuid);
        lastAttackSampleIndex = rotationSampleIndex;
    }

    public void handleAnalyzeResult(double chance) {
        Player bukkitPlayer = player.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline() || !Double.isFinite(chance)) {
            return;
        }

        if (Raveon.INSTANCE.getChecksConfigManager().isAimAiBypassedInRegion(bukkitPlayer)) {
            player.getRotationBuffer().clear();
            return;
        }

        lastProbability = Math.max(0.0D, Math.min(1.0D, chance));

        Raveon.INSTANCE.getViolationManager().addAiProbability(
                bukkitPlayer.getUniqueId(),
                lastProbability,
                true
        );

        updateBuffer(lastProbability);
        Raveon.INSTANCE.getViolationManager().updateAnalysisSnapshot(
                bukkitPlayer.getUniqueId(),
                lastProbability,
                buffer
        );
        sendAiVerbose();

        Raveon.INSTANCE.getMonitorManager().publish(
                bukkitPlayer,
                lastProbability,
                buffer
        );

        if (buffer <= bufferFlagThreshold || !canFlag()) {
            return;
        }

        double bufferAtFlag = buffer;

        registerViolation();
        player.getPunishmentManager().handleViolation(
                this,
                "prob: %.4f, buffer: %.2f".formatted(lastProbability, bufferAtFlag),
                lastProbability
        );

        buffer = bufferResetOnFlag;
        Raveon.INSTANCE.getViolationManager().updateAnalysisSnapshot(
                bukkitPlayer.getUniqueId(),
                lastProbability,
                buffer
        );
    }

    private void updateBuffer(double probability) {
        if (probability > CHEAT_PROBABILITY) {
            buffer += (probability - CHEAT_PROBABILITY) * bufferMultiplier;
        } else if (probability < LEGIT_PROBABILITY) {
            buffer = Math.max(0.0D, buffer - bufferDecrease);
        }
    }

    @Override
    public boolean alert() {
        if (!canAlert()) {
            return false;
        }

        Player bukkitPlayer = player.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) {
            return false;
        }

        String probability = Raveon.INSTANCE.getMainConfigManager().getChanceString(lastProbability);
        String verboseMessage = Raveon.INSTANCE.getMainConfigManager().getAiVerboseMessage()
                .replace("{player}", bukkitPlayer.getName())
                .replace("{probability}", probability)
                .replace("{buffer}", "%.2f".formatted(buffer));

        String alertMessage = Raveon.INSTANCE.getMainConfigManager().getAiAlertMessage()
                .replace("{player}", bukkitPlayer.getName())
                .replace("{vl}", String.valueOf((int) getViolations()))
                .replace("{probability}", probability)
                .replace("{buffer}", "%.2f".formatted(buffer));

        Raveon.INSTANCE.getAlertManager().sendVerbose(verboseMessage);
        Raveon.INSTANCE.getAlertManager().sendAlert(alertMessage);
        Raveon.INSTANCE.getViolationManager().logAlert(
                this,
                "prob: %.4f, buffer: %.2f".formatted(lastProbability, buffer)
        );
        return true;
    }

    @Override
    public boolean alert(String verbose) {
        return alert();
    }

    private void sendAiVerbose() {
        Player bukkitPlayer = player.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) {
            return;
        }

        String probability = Raveon.INSTANCE.getMainConfigManager().getChanceString(lastProbability);
        String verboseMessage = Raveon.INSTANCE.getMainConfigManager().getAiVerboseMessage()
                .replace("{player}", bukkitPlayer.getName())
                .replace("{probability}", probability)
                .replace("{buffer}", "%.2f".formatted(buffer));

        Raveon.INSTANCE.getAlertManager().sendVerbose(verboseMessage);
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
        lastYawToTargetDiff = 0.0F;
        lastPitchToTargetDiff = 0.0F;
    }

    private float calculateSignedYawToTargetDiff(Player player, Entity target) {
        Location eyeLocation = player.getEyeLocation();
        Location targetLocation = getTargetCenter(target);

        double diffX = targetLocation.getX() - eyeLocation.getX();
        double diffZ = targetLocation.getZ() - eyeLocation.getZ();

        float targetYaw = normalizeYaw((float) Math.toDegrees(Math.atan2(-diffX, diffZ)));
        float currentYaw = normalizeYaw(eyeLocation.getYaw());

        return getSignedAngleDelta(targetYaw, currentYaw);
    }

    private float calculateSignedPitchToTargetDiff(Player player, Entity target) {
        Location eyeLocation = player.getEyeLocation();
        Location targetLocation = getTargetCenter(target);

        double diffX = targetLocation.getX() - eyeLocation.getX();
        double diffY = targetLocation.getY() - eyeLocation.getY();
        double diffZ = targetLocation.getZ() - eyeLocation.getZ();
        double horizontalDistance = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float targetPitch = clampPitch((float) -Math.toDegrees(Math.atan2(diffY, horizontalDistance)));
        float currentPitch = clampPitch(eyeLocation.getPitch());

        return targetPitch - currentPitch;
    }

    private Location getTargetCenter(Entity target) {
        return target.getLocation().clone().add(0.0D, target.getHeight() * 0.5D, 0.0D);
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

    public void onReload(CustomConfig config) {
        String path = getConfigName();

        this.bufferFlagThreshold = Math.max(0.0D, config.getDouble(path + ".buffer.flag", 50.0D));
        this.bufferResetOnFlag = Math.max(
                0.0D,
                Math.min(bufferFlagThreshold, config.getDouble(path + ".buffer.reset_on_flag", 25.0D))
        );
        this.bufferMultiplier = Math.max(0.0D, config.getDouble(path + ".buffer.multiplier", 100.0D));
        this.bufferDecrease = Math.max(0.0D, config.getDouble(path + ".buffer.decrease", 0.25D));
    }

}
