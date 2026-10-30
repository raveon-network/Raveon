package ru.raveon.service.analyze;

import lombok.RequiredArgsConstructor;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import ru.raveon.api.models.analyze.AnalyzeService;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class FlatBufferAnalyzeService implements AnalyzeService {
    private final Supplier<ChecksConfigManager> configManager;
    private final AnalyzeBatchDispatcher dispatcher;

    @Override
    public void analyzePlayerFrames(RaveonPlayer player, ModelId model) {
        ChecksConfigManager settings = configManager.get();
        if (!settings.isModelEnabled(model)) return;
        Player bukkit = player.getBukkitPlayer();
        if (bukkit == null || !bukkit.isOnline()) return;
        int step = settings.getAnalysisStep(model);
        try {
            byte[] payload;
            if (model == ModelId.PRO) {
                var frames = player.getRotationBuffer().pollSnapshot(step);
                if (frames == null || frames.isEmpty()) return;
                payload = ModelWire.pro(frames);
            } else {
                var frames = player.getUltraData().pollSnapshot(step);
                if (frames == null || frames.isEmpty()) return;
                payload = ModelWire.ultra(player.getName(), frames);
            }
            dispatcher.enqueue(payload, player, model);
        } catch (RuntimeException error) {
            Bukkit.getLogger()
                    .warning(
                            "[Raveon] "
                                    + model.displayName()
                                    + " request build failed: "
                                    + error.getMessage());
        }
    }
}
