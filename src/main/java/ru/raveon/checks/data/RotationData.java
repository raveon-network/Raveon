package ru.raveon.checks.data;

import org.bukkit.entity.Player;

import ru.raveon.Raveon;
import ru.raveon.checks.Check;
import ru.raveon.checks.type.PacketCheck;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.entity.EntitySnapshot;

import java.util.UUID;

public class RotationData extends Check implements PacketCheck {
    private long lastSnapshotWarning;
    private long skippedSnapshots;

    public RotationData(RaveonPlayer player) {
        super(player);
    }

    public void handleUltraAttack(UUID targetId) {
        if (targetId == null) return;
        UltraData data = player.getUltraData();
        UltraState state = data.activate();
        if (!data.isCombatActive()) {
            player.getCheckManager().invalidate(ModelId.ULTRA);
        }
        state.onAttack(targetId);
        data.extendCombat(player.getCheckManager().getActiveConfig().getCombatTimer(ModelId.ULTRA));
        player.getTrainData(ModelId.ULTRA).tagCombat();
    }

    public void captureUltra(Player bukkit, UltraData data, UltraState state, float yaw, float pitch) {
        if (!bukkit.isOnline() || !data.isCurrent(state)) return;
        var plugin = Raveon.INSTANCE;
        var training = player.getTrainData(ModelId.ULTRA);
        boolean analyze = player.getCheckManager().getAimAI(ModelId.ULTRA).isEnabled();
        if (!analyze && !training.isDatasetsCollecting()) return;
        EntitySnapshot target = null;
        int nearby = 0;
        UUID targetId = data.isCombatActive() ? state.targetId() : null;
        if (targetId != null) {
            target = plugin.getTargetEntityIndex().getSnapshot(targetId);
            Integer count = target != null && target.valid()
                    && target.worldId().equals(bukkit.getWorld().getUID())
                    ? plugin.getTargetEntityIndex().countNearbyTargets(bukkit) : 0;
            if (target == null || count == null) {
                state.capture(bukkit, null, 0, yaw, pitch);
                player.getCheckManager().invalidate(ModelId.ULTRA);
                skippedSnapshots++;
                long now = System.nanoTime();
                if (now - lastSnapshotWarning >= 30_000_000_000L) {
                    lastSnapshotWarning = now;
                    plugin.getLogger().warning(
                            "Ultra snapshot unavailable for " + player.getName()
                                    + "; skipped frames=" + skippedSnapshots);
                }
                return;
            }
            nearby = count;
        }
        var frame = state.capture(bukkit, target, nearby, yaw, pitch);
        var telemetry = player.getCheckManager().getTelemetry();
        boolean collecting = training.isDatasetsCollecting();
        boolean analyzing = analyze && data.isCombatActive()
                && !player.getCheckManager().getActiveConfig().isAimAiBypassedInRegion(bukkit);
        if (collecting || analyzing) {
            telemetry.capture(bukkit, yaw, pitch, frame.datasetFeatures());
            frame = frame.withTelemetry(telemetry.frame());
        } else {
            telemetry.finishFrame();
        }
        if (collecting) training.writeUltraFrame(frame);
        if (!analyzing) {
            data.clearHistory();
            return;
        }
        data.resize(player.getCheckManager().getActiveConfig().getAnalysisSequence(ModelId.ULTRA));
        data.push(frame);
        plugin.getAnalyzeService().analyzePlayerFrames(player, ModelId.ULTRA);
    }

    @Override
    public void reload() {}
}
