package ru.raveon.verification;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.manager.anticheat.ModelDetectionState;
import ru.raveon.model.ModelId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MultimodelVerification {
    private static Path folder;
    private static Plugin plugin;
    private static String defaults;

    public static void main(String[] args) throws Exception {
        folder = Files.createTempDirectory("raveon-multimodel");
        plugin = LanguageTestSupport.plugin(folder);
        try (var input = MultimodelVerification.class.getClassLoader().getResourceAsStream("checks.yml")) {
            defaults = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        verifyIndependent();
        verifyInheritance();
        verifyPrimary();
        verifyEnableAndWindows();
        verifyValidation();
        verifyMitigation();
        System.out.println("MultimodelVerification passed: individual/inherited buffers, source actions, weighted primary, fixed windows, enable gates, reload and mitigation");
    }

    private static void verifyIndependent() throws Exception {
        ModelDetectionState state = new ModelDetectionState();
        state.reload(config(Map.of()));
        require(!state.accept(ModelId.PRO, 1).crossed(), "equality must not flag");
        close(state.buffer(ModelId.PRO), 50, "Pro buffer");
        close(state.buffer(ModelId.ULTRA), 0, "independent Ultra buffer");
        state.accept(ModelId.ULTRA, 1);
        require(state.accept(ModelId.PRO, 1).crossed(), "Pro crossing lost");
        close(state.buffer(ModelId.PRO), 25, "flag did not reset immediately");
        close(state.buffer(ModelId.ULTRA), 50, "Pro reset affected Ultra");
        state.reload(config(Map.of()));
        close(state.buffer(ModelId.ULTRA), 50, "identical reload erased buffers");
        require(state.accept(ModelId.ULTRA, 1).crossed(), "Ultra cannot flag independently");
        require(state.accept(ModelId.PRO, Double.NaN) == null, "NaN accepted");
        require(state.accept(ModelId.PRO, Double.POSITIVE_INFINITY) == null, "infinity accepted");
    }

    private static void verifyInheritance() throws Exception {
        ModelDetectionState state = new ModelDetectionState();
        state.reload(config(Map.of(
                "analyze.models.pro.inherit-buffer", "ultra",
                "analyze.buffer.weights.pro", .5,
                "analyze.models.pro.actions.punish", false,
                "analyze.models.ultra.probability", 1)));
        var first = state.accept(ModelId.PRO, 1);
        require(first.model() == ModelId.PRO && first.bufferOwner() == ModelId.ULTRA, "source and buffer owner mixed");
        close(state.buffer(ModelId.PRO), 25, "source threshold or weight ignored");
        close(state.buffer(ModelId.ULTRA), 25, "inherited buffer not shared");
        state.accept(ModelId.PRO, 0);
        close(state.buffer(ModelId.ULTRA), 24.875, "source weight not applied to decay");
        state.accept(ModelId.ULTRA, 1);
        close(state.buffer(ModelId.ULTRA), 24.875, "source threshold replaced by inheriting model threshold");
        state.accept(ModelId.PRO, 1);
        var result = state.accept(ModelId.PRO, 1);
        require(result.crossed() && !result.actions().punish() && result.actions().alert(),
                "owner actions replaced source actions");
        close(result.atResult(), 74.875, "incorrect crossing snapshot");
        close(state.buffer(ModelId.PRO), 25, "shared reset did not reach inheritor");
        close(state.buffer(ModelId.ULTRA), 25, "shared reset did not reach owner");

        state.reload(config(Map.of("analyze.models.ultra.inherit-buffer", "pro",
                "analyze.models.ultra.actions.alert", false,
                "analyze.models.pro.actions.punish", false)));
        state.accept(ModelId.ULTRA, 1);
        result = state.accept(ModelId.ULTRA, 1);
        require(result.crossed() && result.actions().punish() && !result.actions().alert(),
                "source actions depend on buffer owner's actions");

        state.reload(config(Map.of("analyze.models.pro.inherit-buffer", "ultra",
                "analyze.models.ultra.enabled", false)));
        require(state.accept(ModelId.PRO, 1) != null, "disabled owner disabled active inheritor");
        require(state.accept(ModelId.ULTRA, 1) == null, "disabled owner produced a result");
        close(state.buffer(ModelId.ULTRA), 50, "inheritor cannot use disabled owner's storage");

        state.reload(config(Map.of("analyze.buffer.weights.ultra", 0)));
        require(!state.accept(ModelId.ULTRA, 1).crossed(), "zero weight flagged");
        close(state.buffer(ModelId.ULTRA), 0, "zero weight changed buffer");
        state.reload(config(Map.of("analyze.buffer.weights.pro", Double.MAX_VALUE,
                "analyze.buffer.primary-multiplier", Double.MAX_VALUE)));
        result = state.accept(ModelId.PRO, 1);
        require(result.crossed() && Double.isFinite(result.atResult()), "large finite weights overflow");
        state.reload(config(Map.of()));
        close(state.buffer(ModelId.PRO), 0, "changed policy retained evidence");
    }

    private static void verifyPrimary() throws Exception {
        ModelDetectionState state = new ModelDetectionState();
        var settings = Map.<String, Object>of("analyze.buffer.primary-multiplier", 2,
                "analyze.models.pro.inherit-buffer", "ultra");
        state.reload(config(settings));
        state.accept(ModelId.PRO, .7);
        close(state.buffer(ModelId.ULTRA), 40, "primary increase not amplified");
        state.accept(ModelId.ULTRA, .55);
        close(state.buffer(ModelId.PRO), 45, "secondary increase amplified");
        state.accept(ModelId.PRO, 0);
        close(state.buffer(ModelId.ULTRA), 44.5, "primary decrease not amplified");
        state.accept(ModelId.ULTRA, 0);
        close(state.buffer(ModelId.ULTRA), 44.25, "secondary decrease amplified");

        state.reload(config(Map.of("analyze.buffer.primary-multiplier", 2)));
        state.accept(ModelId.PRO, .7);
        state.accept(ModelId.ULTRA, .7);
        state.accept(ModelId.PRO, 0);
        close(state.buffer(ModelId.PRO), 39.5, "primary own buffer decrease");
        close(state.buffer(ModelId.ULTRA), 20, "priority changed an unrelated buffer");

        state.reload(config(Map.of("analyze.primary", "ultra", "analyze.buffer.primary-multiplier", 2,
                "analyze.models.pro.inherit-buffer", "ultra")));
        state.accept(ModelId.ULTRA, .7);
        var crossing = state.accept(ModelId.PRO, .7);
        require(crossing.crossed() && crossing.model() == ModelId.PRO && crossing.actions().punish(),
                "primary monopolizes punishment");
    }

    private static void verifyEnableAndWindows() throws Exception {
        var fixed = config(Map.of("analyze.sequence", 999,
                "analyze.models.pro.sequence", 123, "analyze.models.ultra.sequence", 321));
        require(fixed.getAnalysisSequence(ModelId.PRO) == 50
                && fixed.getAnalysisSequence(ModelId.ULTRA) == 64, "window length is configurable");
        ModelDetectionState state = new ModelDetectionState();
        var off = config(Map.of("analyze.enabled", false));
        state.reload(off);
        for (ModelId model : ModelId.values())
            require(!off.isModelEnabled(model) && state.accept(model, 1) == null, "master switch bypassed");
        var bothOff = config(Map.of("analyze.models.pro.enabled", false, "analyze.models.ultra.enabled", false));
        state.reload(bothOff);
        for (ModelId model : ModelId.values())
            require(!bothOff.isModelEnabled(model) && state.accept(model, 1) == null, "disabled model analyzed");
        state.reload(config(Map.of("analyze.models.pro.enabled", false)));
        require(state.accept(ModelId.PRO, 1) == null && state.accept(ModelId.ULTRA, 1) != null,
                "per-model switch is not independent");
    }

    private static void verifyValidation() throws Exception {
        for (var invalid : java.util.List.of(
                Map.<String, Object>of("analyze.models.pro.actions", "invalid"),
                Map.<String, Object>of("analyze.models.pro.inherit-buffer", "pro"),
                Map.<String, Object>of("analyze.models.pro.inherit-buffer", "ultra",
                        "analyze.models.ultra.inherit-buffer", "pro"),
                Map.<String, Object>of("analyze.models.pro.inherit-buffer", "unknown"),
                Map.<String, Object>of("analyze.buffer.weights.pro", -1),
                Map.<String, Object>of("analyze.buffer.primary-multiplier", .5),
                Map.<String, Object>of("analyze.models.pro.mitigation-weight", Double.NaN),
                Map.<String, Object>of("analyze.models.pro.actions.punish", "yes please"),
                Map.<String, Object>of("analyze.models.pro.step", 51),
                Map.<String, Object>of("analyze.models.ultra.step", 65),
                Map.<String, Object>of("analyze.mitigation.full-effect-score", 3),
                Map.<String, Object>of("analyze.mitigation.neutral-probability", 0),
                Map.<String, Object>of("analyze.mitigation.half-life-seconds", 0))) {
            try {
                config(invalid);
                throw new AssertionError("invalid configuration accepted: " + invalid);
            } catch (IllegalArgumentException expected) {}
        }
    }

    private static void verifyMitigation() throws Exception {
        AtomicLong time = new AtomicLong();
        ModelDetectionState state = new ModelDetectionState(time::get);
        var disabled = config(Map.of());
        state.reload(disabled);
        for (int i = 0; i < 20; i++) state.accept(ModelId.PRO, 1);
        close(state.damageMultiplier(disabled), 1, "default damage changed");
        var active = config(Map.of("analyze.mitigation.enabled", true,
                "analyze.mitigation.min-answers", 1, "analyze.mitigation.activation-score", 0,
                "analyze.mitigation.full-effect-score", 1, "analyze.models.pro.actions.punish", false,
                "analyze.models.ultra.step", 10));
        state.reload(active);
        state.accept(ModelId.ULTRA, .9);
        double first = state.mitigationScore();
        state.accept(ModelId.PRO, .9);
        close(state.mitigationScore(), 2 * first, "secondary contribution lost");
        require(state.damageMultiplier(active) < 1 && state.damageMultiplier(active) >= .5, "mitigation role or cap failed");
        double score = state.mitigationScore();
        time.addAndGet(TimeUnit.SECONDS.toNanos(5));
        state.accept(ModelId.PRO, .5);
        close(state.mitigationScore(), score * Math.pow(.5, 5.0 / 30), "time decay");
        time.addAndGet(TimeUnit.SECONDS.toNanos(10));
        close(state.damageMultiplier(active), 1, "stale result reduces damage");
        state.accept(ModelId.PRO, .5);
        close(state.mitigationScore(), 0, "stale evidence retained");
        var noRole = config(Map.of("analyze.mitigation.enabled", true,
                "analyze.models.pro.actions.mitigate", false, "analyze.models.ultra.actions.mitigate", false));
        state.reload(noRole);
        for (int i = 0; i < 20; i++) state.accept(ModelId.PRO, 1);
        close(state.mitigationScore(), 0, "disabled mitigation role contributed");
        var huge = config(Map.of("analyze.mitigation.enabled", true,
                "analyze.models.pro.mitigation-weight", Double.MAX_VALUE,
                "analyze.models.ultra.mitigation-weight", Double.MAX_VALUE));
        state.reload(huge);
        state.accept(ModelId.PRO, 1);
        require(Double.isFinite(state.mitigationScore()) && state.mitigationScore() > 0, "mitigation weight sum overflow");
        close(state.damageMultiplier(active), 1, "old mitigation config used");
    }

    static ChecksConfigManager config(Map<String, Object> values) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(defaults);
        yaml.set("analyze.enabled", true);
        yaml.set("analyze.buffer.primary-multiplier", 1);
        yaml.set("analyze.models.pro.probability", .5);
        yaml.set("analyze.models.ultra.probability", .5);
        values.forEach(yaml::set);
        yaml.save(folder.resolve("checks.yml").toFile());
        return new ChecksConfigManager(plugin);
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-8, message + ": " + actual + " != " + expected);
    }
}
