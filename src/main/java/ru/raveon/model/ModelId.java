package ru.raveon.model;

import java.util.Locale;

public enum ModelId {
    PRO(1, "Pro", 8, 50, 10),
    ULTRA(2, "Ultra", ru.raveon.api.models.UltraFrame.INFERENCE_FEATURE_COUNT, 64, 32);
    private final int wireId;
    private final int features;
    private final int sequence;
    private final int step;
    private final String displayName;

    ModelId(int wireId, String displayName, int features, int sequence, int step) {
        this.wireId = wireId;
        this.displayName = displayName;
        this.features = features;
        this.sequence = sequence;
        this.step = step;
    }

    public int wireId() {
        return wireId;
    }

    public String displayName() {
        return displayName;
    }

    public int features() {
        return features;
    }

    public int sequence() {
        return sequence;
    }

    public int step() {
        return step;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ModelId parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static ModelId fromWire(int id) {
        for (ModelId model : values()) {
            if (model.wireId == id) {
                return model;
            }
        }
        throw new IllegalArgumentException("Unknown model id: " + id);
    }
}
