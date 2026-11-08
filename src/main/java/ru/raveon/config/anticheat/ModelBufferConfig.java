package ru.raveon.config.anticheat;

import org.bukkit.configuration.file.FileConfiguration;
import ru.raveon.model.ModelId;
import ru.raveon.model.ModelPolicy;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import ru.raveon.config.anticheat.ChecksConfigManager.AnalysisSettings;

/** Selects one complete buffer policy. Local values never expand API model access. */
public final class ModelBufferConfig {
    private ModelBufferConfig() {}

    public static ModelPolicy.Buffer resolve(FileConfiguration config, ModelId model, ModelPolicy.Buffer remote) {
        String path = "analyze.models." + model.key();
        String block = path + ".buffer";
        if (config.contains(block) && !config.isConfigurationSection(block))
            throw invalid(block, "must be a section");
        String source = text(config, block + ".source", "website");
        if (!source.equals("website") && !source.equals("local"))
            throw invalid(block + ".source", "must be website or local");
        if (source.equals("website") && remote != null) return remote;

        double flag = number(config, block + ".flag", "analyze.buffer.flag", 50, .001, 1_000_000);
        double threshold = number(config, block + ".threshold", path + ".probability", .85, 0, 1);
        String inherit = config.contains(block + ".inherit") ? text(config, block + ".inherit", "")
                : text(config, path + ".inherit-buffer", "");
        ModelId owner = inherit.isEmpty() ? null : ModelId.parse(inherit);
        if (owner == model) throw invalid(block + ".inherit", "cannot refer to itself");
        return new ModelPolicy.Buffer(flag,
                number(config, block + ".reset", "analyze.buffer.reset_on_flag", 25, 0, flag),
                number(config, block + ".multiplier", "analyze.buffer.multiplier", 100, 0, 1_000_000),
                number(config, block + ".decrease", "analyze.buffer.decrease", .25, 0, 1_000_000),
                number(config, block + ".weight", "analyze.buffer.weights." + model.key(), 1, 0, 1000),
                number(config, block + ".primary-multiplier", "analyze.buffer.primary-multiplier", 2, 1, 1000),
                threshold, number(config, block + ".legit-threshold", null, .1, 0, threshold), owner);
    }

    public static Map<ModelId, AnalysisSettings> owners(Map<ModelId, AnalysisSettings> models, Set<ModelId> licensed) {
        for (var entry : models.entrySet()) {
            ModelId owner = entry.getValue().bufferOwner();
            if (models.get(owner).bufferOwner() != owner)
                throw invalid("analyze.models." + entry.getKey().key() + ".buffer.inherit", "chains and cycles are not supported");
        }
        var result = new EnumMap<ModelId, AnalysisSettings>(models);
        for (var entry : models.entrySet()) {
            ModelId model = entry.getKey(), owner = entry.getValue().bufferOwner();
            if (owner != model && (!models.get(owner).enabled() || !licensed.contains(owner))) {
                var source = entry.getValue();
                result.put(model, new AnalysisSettings(source.enabled(), source.window(), source.step(), source.combatMillis(),
                        source.probability(), source.actions(), source.mitigationWeight(), model, source.buffer()));
            }
        }
        return Map.copyOf(result);
    }

    private static double number(FileConfiguration config, String path, String legacy, double fallback, double min, double max) {
        Object raw = config.get(path);
        if (raw == null && legacy != null) raw = config.get(legacy);
        if (raw != null && !(raw instanceof Number)) throw invalid(path, "must be a number");
        double value = raw == null ? fallback : ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < min || value > max)
            throw invalid(path, "must be finite and in " + min + ".." + max);
        return value;
    }

    private static String text(FileConfiguration config, String path, String fallback) {
        Object raw = config.get(path);
        if (raw == null) return fallback;
        if (!(raw instanceof String value)) throw invalid(path, "must be a string");
        return value.trim();
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException("checks.yml: " + path + " " + reason);
    }
}
