package ru.raveon.service.analyze;

import org.bukkit.configuration.file.YamlConfiguration;
import ru.raveon.config.anticheat.ModelBufferConfig;
import ru.raveon.config.anticheat.ChecksConfigManager.*;
import ru.raveon.manager.anticheat.DetectionSettings;
import ru.raveon.manager.anticheat.ModelDetectionState;
import ru.raveon.model.ModelId;
import ru.raveon.model.ModelPolicy;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public final class BufferInheritanceContractTest {
    public static void run() throws Exception {
        var source = new ModelPolicy.Buffer(500, 100, 7, 17, 2, 3, .2, .05, ModelId.ULTRA);
        var owner = new ModelPolicy.Buffer(10, 3, 100, .75, 7, 5, .9, .4, null);
        var models = Map.of(ModelId.PRO, model(source, true, false), ModelId.ULTRA, model(owner, true, true));
        var state = new ModelDetectionState();
        state.reload(settings(models, ModelId.ULTRA));
        state.accept(ModelId.PRO, .88);
        near(state.buffer(ModelId.ULTRA), 0, "Owner cheat threshold, not the source threshold");
        var flag = state.accept(ModelId.PRO, .96);
        check(flag.crossed() && flag.bufferOwner() == ModelId.ULTRA && flag.model() == ModelId.PRO, "Shared flag belongs to the owner buffer");
        near(flag.atResult(), 12, "Owner multiplier and source weight; primary owner adds no source bonus");
        near(state.buffer(ModelId.PRO), 3, "Owner reset and shared reads");
        check(!flag.actions().alert() && !flag.actions().punish(), "Source action switches remain independent of shared arithmetic");
        state.accept(ModelId.PRO, .5);
        near(state.buffer(ModelId.ULTRA), 3, "Neutral probability leaves shared buffer unchanged");
        state.accept(ModelId.PRO, .3);
        near(state.buffer(ModelId.ULTRA), 1.5, "Owner legitimate threshold and decay, weighted by the source");
        state.reload(settings(models, ModelId.PRO));
        near(state.buffer(ModelId.PRO), 1.5, "Changing primary preserves accumulated buffer");
        near(state.accept(ModelId.PRO, .96).atResult(), 37.5, "Primary multiplier still belongs to the answer source");

        var disabled = Map.of(ModelId.PRO, model(source, true, true), ModelId.ULTRA, model(owner, false, true));
        check(ModelBufferConfig.owners(disabled, Set.of(ModelId.PRO, ModelId.ULTRA)).get(ModelId.PRO).bufferOwner() == ModelId.PRO,
                "Locally disabled owner falls back to own buffer");
        check(ModelBufferConfig.owners(models, Set.of(ModelId.PRO)).get(ModelId.PRO).bufferOwner() == ModelId.PRO,
                "Unlicensed owner falls back to own buffer without granting access");
        var cycle = new ModelPolicy.Buffer(10, 3, 100, .75, 1, 2, .9, .1, ModelId.PRO);
        reject(() -> ModelBufferConfig.owners(Map.of(ModelId.PRO, model(source, true, true), ModelId.ULTRA, model(cycle, true, true)),
                Set.of(ModelId.PRO, ModelId.ULTRA)), "Cycles rejected before disabled-owner fallback");

        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            analyze:
              buffer:
                flag: 70
                reset_on_flag: 20
                multiplier: 80
                decrease: 0.5
                primary-multiplier: 4
                weights:
                  pro: 0.5
              models:
                pro:
                  probability: 0.8
                  inherit-buffer: ultra
            """);
        var legacy = ModelBufferConfig.resolve(yaml, ModelId.PRO, null);
        check(legacy.flag() == 70 && legacy.reset() == 20 && legacy.multiplier() == 80 && legacy.decrease() == .5
                && legacy.weight() == .5 && legacy.primaryMultiplier() == 4 && legacy.threshold() == .8 && legacy.inherit() == ModelId.ULTRA,
                "Legacy files retain their numeric values and inheritance");
        check(ModelBufferConfig.resolve(yaml, ModelId.PRO, source) == source, "Website wins by default");
        yaml.set("analyze.models.pro.buffer.source", "local");
        yaml.set("analyze.models.pro.buffer.threshold", .92);
        yaml.set("analyze.models.pro.buffer.inherit", "");
        var local = ModelBufferConfig.resolve(yaml, ModelId.PRO, source);
        check(local.threshold() == .92 && local.inherit() == null && local.flag() == 70, "Local mode overrides website buffer and reads old fallbacks");
        yaml.set("analyze.models.pro.buffer.source", "website");
        yaml.set("analyze.models.pro.buffer.threshold", "not a number");
        check(ModelBufferConfig.resolve(yaml, ModelId.PRO, source) == source, "Inactive local numeric values cannot break a valid website profile");
        yaml.set("analyze.models.pro.buffer.source", "local");
        reject(() -> ModelBufferConfig.resolve(yaml, ModelId.PRO, source), "Invalid local number rejected");
        yaml.set("analyze.models.pro.buffer.threshold", .92);
        for (var invalid : Map.<String, Object>of("source", "typo", "flag", Double.NaN, "reset", 71,
                "legit-threshold", .99, "weight", -1, "inherit", "pro").entrySet()) {
            var copy = new YamlConfiguration(); copy.loadFromString(yaml.saveToString());
            copy.set("analyze.models.pro.buffer." + invalid.getKey(), invalid.getValue());
            reject(() -> ModelBufferConfig.resolve(copy, ModelId.PRO, source), "Invalid local policy rejected: " + invalid.getKey());
        }
        try (var in = BufferInheritanceContractTest.class.getClassLoader().getResourceAsStream("checks.yml")) {
            check(in != null, "Packaged checks.yml exists");
            var defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (ModelId id : ModelId.values()) {
                var parsed = ModelBufferConfig.resolve(defaults, id, null);
                check(parsed.flag() == 50 && parsed.threshold() == .85 && parsed.inherit() == null, "Packaged per-model defaults parse");
                check(ModelBufferConfig.resolve(defaults, id, owner) == owner, "Packaged defaults preserve website control");
                defaults.set("analyze.models." + id.key() + ".buffer.source", "local");
                defaults.set("analyze.models." + id.key() + ".buffer.flag", 80);
                check(ModelBufferConfig.resolve(defaults, id, owner).flag() == 80, "Packaged local mode is effective");
            }
        }
        System.out.println("Owner thresholds, local buffer selection, legacy configuration and inheritance safety checks passed");
    }

    private static AnalysisSettings model(ModelPolicy.Buffer b, boolean enabled, boolean actions) {
        return new AnalysisSettings(enabled, 64, 16, 3200, b.threshold(), new ModelActions(actions, actions, actions), 1,
                b.inherit() == null ? ModelId.ULTRA : b.inherit(), b);
    }

    private record Settings(Map<ModelId, AnalysisSettings> getModelSettings, BufferSettings getBufferSettings,
            ModelId getPrimaryModel, MitigationSettings getMitigationSettings) implements DetectionSettings {
        public boolean isAnalysisEnabled() { return true; }
        public boolean isModelEnabled(ModelId m) { return getModelSettings.get(m).enabled(); }
        public int getAnalysisStep(ModelId m) { return getModelSettings.get(m).step(); }
        public int getAnalysisSequence(ModelId m) { return getModelSettings.get(m).window(); }
    }
    private static Settings settings(Map<ModelId, AnalysisSettings> models, ModelId primary) {
        return new Settings(models, new BufferSettings(50,25,100,.25,2,Map.of(ModelId.PRO,1.0,ModelId.ULTRA,1.0)),
                primary, new MitigationSettings(false,.5,30000,10000,3,3,10,20,.5));
    }
    private static void reject(Runnable call, String message) {
        boolean rejected = false; try { call.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-8, message); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
