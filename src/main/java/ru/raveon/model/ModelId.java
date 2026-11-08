package ru.raveon.model;

import java.util.Locale;
import java.util.Objects;

/** Model identity is independent of the supported feature collector. */
public final class ModelId implements Comparable<ModelId> {
    public static final int MAX_MODELS = 32;
    public static final ModelId PRO = new ModelId("pro", 1, "pro");
    public static final ModelId ULTRA = new ModelId("ultra", 2, "ultra");
    private final String key;
    private final int wireId;
    private final String capture;

    private ModelId(String key, int wireId, String capture) {
        this.key = key; this.wireId = wireId; this.capture = capture;
    }
    public static ModelId define(String id, int wireId, String capture) {
        ModelId parsed = parse(id);
        if (!parsed.key.equals(id) || wireId < 1 || wireId > 255
                || !(capture.equals("pro") || capture.equals("ultra")))
            throw new IllegalArgumentException("Invalid model definition: " + id);
        if (parsed.equals(PRO) || parsed.equals(ULTRA)) {
            if (wireId != parsed.wireId || !capture.equals(parsed.capture))
                throw new IllegalArgumentException("Built-in model format/wire ID cannot change");
            return parsed;
        }
        if (wireId < 3) throw new IllegalArgumentException("Wire IDs 1 and 2 are reserved");
        return new ModelId(id, wireId, capture);
    }
    public static ModelId parse(String value) {
        String id = Objects.requireNonNull(value).trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z][a-z0-9_]{0,31}") || java.util.Set.of("all", "auto", "none", "off", "both").contains(id))
            throw new IllegalArgumentException("Invalid model ID: " + id);
        if (id.equals("pro")) return PRO;
        if (id.equals("ultra")) return ULTRA;
        return new ModelId(id, 0, "");
    }
    /** Legacy collectors/training formats; active inference models come from the API profile. */
    public static ModelId[] values() { return new ModelId[] { PRO, ULTRA }; }
    public static ModelId fromWire(int id) {
        for (ModelId model : values()) if (model.wireId == id) return model;
        throw new IllegalArgumentException("Unknown legacy wire ID: " + id);
    }
    public int wireId() { if (wireId == 0) throw new IllegalStateException("Unresolved model: " + key); return wireId; }
    public String capture() { return capture; }
    public boolean proCapture() { return capture.equals("pro"); }
    private void requireCapture() { if (capture.isEmpty()) throw new IllegalStateException("Unresolved model: " + key); }
    public int features() { requireCapture(); return proCapture() ? 8 : ru.raveon.api.models.UltraFrame.INFERENCE_FEATURE_COUNT; }
    public int sequence() { requireCapture(); return proCapture() ? 50 : 64; }
    public int step() { requireCapture(); return proCapture() ? 10 : 32; }
    public String displayName() { return equals(PRO) ? "Pro" : equals(ULTRA) ? "Ultra" : key; }
    public String key() { return key; }
    public String name() { return key.toUpperCase(Locale.ROOT); }
    @Override public boolean equals(Object other) { return other instanceof ModelId model && key.equals(model.key); }
    @Override public int hashCode() { return key.hashCode(); }
    @Override public int compareTo(ModelId other) { return key.compareTo(other.key); }
    @Override public String toString() { return key; }
}
