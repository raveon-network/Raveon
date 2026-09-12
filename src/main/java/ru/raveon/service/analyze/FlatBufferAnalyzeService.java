package ru.raveon.service.analyze;

import com.google.flatbuffers.FlatBufferBuilder;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.api.models.analyze.AnalyzeService;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.player.RaveonPlayer;

import java.util.List;

@RequiredArgsConstructor
public final class FlatBufferAnalyzeService implements AnalyzeService {

    private final ChecksConfigManager configManager;
    private final AnalyzeBatchDispatcher dispatcher;

    @Override
    public void analyzePlayerFrames(RaveonPlayer raveonPlayer) {
        if (!raveonPlayer.getRotationBuffer().isFull()) {
            return;
        }

        Player bukkitPlayer = raveonPlayer.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) {
            return;
        }

        List<RotationFrame> frames = raveonPlayer.getRotationBuffer()
                .pollSnapshot(configManager.getAnalysisStep());

        if (frames == null || frames.isEmpty()) {
            return;
        }

        final byte[] payload;
        try {
            payload = encodeRequest(frames);
        } catch (RuntimeException exception) {
            Bukkit.getLogger().warning(
                    "[RaveonAI] FlatBuffers request build failed: " + exception.getMessage()
            );
            return;
        }

        dispatcher.enqueue(payload, raveonPlayer);
    }

    private byte[] encodeRequest(List<RotationFrame> frames) {
        float[] features = flattenFeatures(frames);

        int initialCapacity = 64 + features.length * Float.BYTES;
        FlatBufferBuilder builder = new FlatBufferBuilder(initialCapacity);

        int featuresOffset = createFeaturesVector(builder, features);
        int requestOffset = createRequest(builder, frames.size(), featuresOffset);
        builder.finish(requestOffset, "GAIQ");
        return builder.sizedByteArray();
    }

    private int createFeaturesVector(FlatBufferBuilder builder, float[] features) {
        builder.startVector(Float.BYTES, features.length, Float.BYTES);
        for (int index = features.length - 1; index >= 0; index--) {
            builder.addFloat(features[index]);
        }
        return builder.endVector();
    }

    private int createRequest(FlatBufferBuilder builder, int frameCount, int featuresOffset) {
        builder.startTable(3);
        builder.addOffset(2, featuresOffset, 0);
        builder.addInt(1, frameCount, 0);
        return builder.endTable();
    }

    private float[] flattenFeatures(List<RotationFrame> frames) {
        float[] features = new float[frames.size() * 8];

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            RotationFrame frame = frames.get(frameIndex);
            int base = frameIndex * 8;

            features[base] = frame.getDeltaYaw();
            features[base + 1] = frame.getDeltaPitch();
            features[base + 2] = frame.getAccelYaw();
            features[base + 3] = frame.getAccelPitch();
            features[base + 4] = frame.getJerkYaw();
            features[base + 5] = frame.getJerkPitch();
            features[base + 6] = frame.getGcdErrorYaw();
            features[base + 7] = frame.getGcdErrorPitch();
        }

        return features;
    }
}
