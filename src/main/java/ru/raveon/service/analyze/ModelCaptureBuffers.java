package ru.raveon.service.analyze;

import java.util.*;
import ru.raveon.model.ModelId;
import ru.raveon.model.PluginModelProfile;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.api.models.UltraFrame;
import ru.raveon.utils.math.RotationRingBuffer;

/** Independent cadence/window per API model, even when feature collectors are shared. */
public final class ModelCaptureBuffers {
    private final Map<ModelId, RotationRingBuffer<RotationFrame>> pro = new LinkedHashMap<>();
    private final Map<ModelId, RotationRingBuffer<UltraFrame>> ultra = new LinkedHashMap<>();

    public synchronized void configure(PluginModelProfile profile) {
        pro.clear(); ultra.clear();
        profile.models().forEach((id, spec) -> {
            if (id.proCapture()) pro.put(id, new RotationRingBuffer<>(spec.window()));
            else ultra.put(id, new RotationRingBuffer<>(spec.window()));
        });
    }
    public synchronized void clear(ModelId id) {
        if (pro.containsKey(id)) pro.get(id).clear();
        if (ultra.containsKey(id)) ultra.get(id).clear();
    }
    public synchronized void addPro(RotationFrame frame) { pro.values().forEach(b -> b.addFrame(frame)); }
    public synchronized void addUltra(UltraFrame frame) { ultra.values().forEach(b -> b.addFrame(frame)); }
    public synchronized List<RotationFrame> pro(ModelId id, int step) {
        var buffer = pro.get(id); return buffer == null ? null : buffer.pollSnapshot(step);
    }
    public synchronized List<UltraFrame> ultra(ModelId id, int step) {
        var buffer = ultra.get(id); return buffer == null ? null : buffer.pollSnapshot(step);
    }
}
