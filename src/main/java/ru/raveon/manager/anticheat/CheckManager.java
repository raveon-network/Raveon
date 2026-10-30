package ru.raveon.manager.anticheat;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import lombok.Getter;

import org.bukkit.entity.Player;

import ru.raveon.Raveon;
import ru.raveon.checks.data.RotationData;
import ru.raveon.checks.data.TelemetryPackets;
import ru.raveon.checks.data.TelemetryState;
import ru.raveon.checks.data.TelemetryUpdate;
import ru.raveon.checks.impl.ai.AimAI;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.SchedulerUtils;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Getter
public class CheckManager {
    private static final int MAX_PENDING_PACKETS = 128;
    private static final int PACKETS_PER_TICK = 16;

    private final ModelDetectionState detectionState = new ModelDetectionState();
    private final RotationData rotationData;
    private final TelemetryState telemetry = new TelemetryState();
    private final RaveonPlayer player;
    private final Map<ModelId, AimAI> aimChecks = new EnumMap<>(ModelId.class);
    private final Map<ModelId, AtomicLong> generations = new EnumMap<>(ModelId.class);
    private final PacketWorkQueue<PacketInput> pending =
            new PacketWorkQueue<>(MAX_PENDING_PACKETS, PACKETS_PER_TICK);
    private final AtomicLong droppedPackets = new AtomicLong();
    private long lastOverflowWarning;
    private long reportedDroppedPackets;
    private volatile ChecksConfigManager activeConfig;

    public CheckManager(RaveonPlayer player) {
        this.player = player;
        this.rotationData = new RotationData(player);
        for (ModelId model : ModelId.values()) {
            aimChecks.put(model, new AimAI(player, model));
            generations.put(model, new AtomicLong());
        }
        reload();
    }

    public AimAI getAimAI() {
        return getAimAI(ModelId.PRO);
    }

    public AimAI getAimAI(ModelId model) {
        return aimChecks.get(model);
    }

    public long getGeneration(ModelId model) {
        return generations.get(model).get();
    }

    public void invalidate(ModelId model) {
        generations.get(model).incrementAndGet();
        if (model == ModelId.PRO) {
            if (player.getRotationBuffer() != null) player.getRotationBuffer().clear();
        } else {
            player.getUltraData().clearHistory();
        }
    }

    public void onPacketReceive(PacketReceiveEvent packet) {
        if (packet.isCancelled()
                || activeConfig == Raveon.INSTANCE.getChecksConfigManager()
                        && !isRequested(ModelId.PRO) && !isRequested(ModelId.ULTRA)) return;
        Player bukkit = packet.getPlayer();
        if (bukkit == null) return;
        if (!WrapperPlayClientPlayerFlying.isFlying(packet.getPacketType())) {
            if (isRequested(ModelId.ULTRA)) {
                try {
                    handleTelemetry(bukkit, TelemetryPackets.receive(packet));
                } catch (IllegalArgumentException ignored) {}
            }
            return;
        }
        var flying = new WrapperPlayClientPlayerFlying(packet);
        boolean rotated = flying.hasRotationChanged();
        float yaw = rotated ? flying.getLocation().getYaw() : 0.0F;
        float pitch = rotated ? flying.getLocation().getPitch() : 0.0F;
        if (rotated && (!Float.isFinite(yaw) || !Float.isFinite(pitch))) {
            enqueue(bukkit, new PacketInput(Kind.RESET, false, 0.0F, 0.0F, -1, null));
            return;
        }
        if (!isRequested(ModelId.ULTRA)) {
            enqueue(bukkit, new PacketInput(Kind.MOVEMENT, rotated, yaw, pitch, -1, null));
            return;
        }
        var location = flying.getLocation();
        boolean positioned = flying.hasPositionChanged();
        double x = positioned ? location.getX() : 0;
        double y = positioned ? location.getY() : 0;
        double z = positioned ? location.getZ() : 0;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 32_000_000 || Math.abs(y) > 32_000_000 || Math.abs(z) > 32_000_000) {
            enqueue(bukkit, new PacketInput(Kind.MOVEMENT, rotated, yaw, pitch, -1, null));
            return;
        }
        enqueue(bukkit, new PacketInput(Kind.MOVEMENT, rotated, yaw, pitch, -1,
                new TelemetryUpdate(TelemetryUpdate.Kind.MOVEMENT, new double[] {
                        positioned ? 1 : 0, x, y, z, flying.isOnGround() ? 1 : 0, System.nanoTime()})));
    }

    public void handleAttack(Player bukkit, int entityId) {
        if (bukkit != null && (isRequested(ModelId.PRO) || isRequested(ModelId.ULTRA)))
            enqueue(bukkit, new PacketInput(Kind.ATTACK, false, 0.0F, 0.0F, entityId, null));
    }

    public void handleTelemetry(Player bukkit, TelemetryUpdate update) {
        if (bukkit != null && update != null && isRequested(ModelId.ULTRA))
            enqueue(bukkit, new PacketInput(Kind.TELEMETRY, false, 0, 0, -1, update));
    }

    private boolean isRequested(ModelId model) {
        return getAimAI(model).isEnabled() || player.getTrainData(model).isDatasetsCollecting();
    }

    private void enqueue(Player bukkit, PacketInput input) {
        PacketWorkQueue.Offer offered = pending.offer(input);
        if (offered.overflow()) {
            droppedPackets.addAndGet(MAX_PENDING_PACKETS);
            generations.values().forEach(AtomicLong::incrementAndGet);
        }
        if (offered.schedule()) scheduleDrain(bukkit);
    }

    private void scheduleDrain(Player bukkit) {
        try {
            SchedulerUtils.runEntity(Raveon.INSTANCE, bukkit, () -> drain(bukkit), this::close);
        } catch (RuntimeException exception) {
            close();
            Raveon.INSTANCE.getLogger().warning(
                    "Cannot schedule packet processing for " + player.getName() + ": " + exception.getMessage());
        }
    }

    private void drain(Player bukkit) {
        if (Raveon.INSTANCE.getPlayerDataManager().getPlayer(player.getUuid()) != player
                || !bukkit.isOnline()) {
            close();
            return;
        }
        refreshSettings();
        PacketWorkQueue.Batch<PacketInput> batch = pending.takeBatch();
        try {
            if (batch.reset()) {
                resetWindows();
                long now = System.nanoTime();
                if (droppedPackets.get() != reportedDroppedPackets
                        && now - lastOverflowWarning >= 30_000_000_000L) {
                    reportedDroppedPackets = droppedPackets.get();
                    lastOverflowWarning = now;
                    Raveon.INSTANCE.getLogger().warning(
                            "Packet analysis queue overflow for " + player.getName()
                                    + "; discarded packets=" + droppedPackets.get());
                }
            }
            for (PacketInput input : batch.entries()) process(bukkit, input);
        } finally {
            if (pending.completeBatch()) scheduleDrain(bukkit);
        }
    }

    private void process(Player bukkit, PacketInput input) {
        if (Raveon.INSTANCE.getPlayerDataManager().getPlayer(player.getUuid()) != player
                || !bukkit.isOnline()) return;
        refreshSettings();
        if (input.kind == Kind.RESET) {
            resetWindows();
            return;
        }
        if (input.kind == Kind.TELEMETRY) {
            if (input.telemetry.kind() == TelemetryUpdate.Kind.TELEPORT) {
                invalidate(ModelId.ULTRA);
                player.getUltraData().reset();
            }
            telemetry.apply(bukkit, input.telemetry);
            return;
        }
        if (input.kind == Kind.ATTACK) {
            UUID targetId = Raveon.INSTANCE.getTargetEntityIndex().getUniqueIdByEntityId(input.entityId);
            if (isRequested(ModelId.ULTRA)) telemetry.attack(bukkit, input.entityId, targetId);
            if (targetId == null) return;
            if (isRequested(ModelId.PRO)) player.markAttack(targetId);
            if (isRequested(ModelId.ULTRA)) rotationData.handleUltraAttack(targetId);
            return;
        }
        boolean ultraRequested = isRequested(ModelId.ULTRA);
        if (ultraRequested && input.telemetry == null) {
            invalidate(ModelId.ULTRA);
            player.getUltraData().reset();
            telemetry.reset();
            ultraRequested = false;
        }
        if (ultraRequested) telemetry.apply(bukkit, input.telemetry);
        var data = player.getUltraData();
        var state = ultraRequested ? data.activate() : null;
        if (state != null) state.onFlying(input.rotated);
        if (!input.rotated) return;
        if (isRequested(ModelId.PRO)) getAimAI().handleRotation(bukkit, input.yaw, input.pitch);
        if (state != null) rotationData.captureUltra(bukkit, data, state, input.yaw, input.pitch);
    }

    private void resetWindows() {
        telemetry.reset();
        for (ModelId model : ModelId.values()) invalidate(model);
        player.getUltraData().reset();
        aimChecks.values().forEach(AimAI::resetRotation);
    }

    public void reload() {
        pending.reset();
        resetWindows();
        ChecksConfigManager settings = Raveon.INSTANCE.getChecksConfigManager();
        detectionState.reload(settings);
        aimChecks.values().forEach(check -> check.reloadFrom(settings));
        activeConfig = settings;
        if (player.getRotationBuffer() != null) player.resizeRotationBuffer(settings.getAnalysisSequence());
    }

    public void refreshSettings() {
        if (activeConfig != Raveon.INSTANCE.getChecksConfigManager()) reload();
    }

    public void close() {
        pending.close();
        generations.values().forEach(AtomicLong::incrementAndGet);
        player.getUltraData().reset();
    }

    private enum Kind { MOVEMENT, ATTACK, TELEMETRY, RESET }
    private record PacketInput(Kind kind, boolean rotated, float yaw, float pitch, int entityId, TelemetryUpdate telemetry) {}
}
