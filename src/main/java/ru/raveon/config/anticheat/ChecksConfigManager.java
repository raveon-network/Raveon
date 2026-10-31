package ru.raveon.config.anticheat;

import lombok.Getter;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.integration.worldguard.WorldGuardRegionBypassConfig;
import ru.raveon.integration.worldguard.WorldGuardRegionBypassService;
import ru.raveon.model.ModelId;

import java.util.EnumMap;
import java.util.Map;

@Getter
public class ChecksConfigManager extends ConfigManager {
    private boolean analysisEnabled;
    private String analyzeServer;
    private String apiKey;
    private WorldGuardRegionBypassService worldGuardRegionBypassService;
    private Map<ModelId, AnalysisSettings> modelSettings;
    private BufferSettings bufferSettings;
    private ModelId primaryModel;
    private MitigationSettings mitigationSettings;

    public record ModelActions(boolean alert, boolean mitigate, boolean punish) {}

    public record AnalysisSettings(boolean enabled, int step, long combatMillis,
            double probability, ModelActions actions, double mitigationWeight, ModelId bufferOwner) {}

    public record BufferSettings(double flag, double reset, double multiplier, double decrease,
            double primaryMultiplier, Map<ModelId, Double> weights) {}

    public record MitigationSettings(boolean enabled, double neutralProbability,
            long halfLifeMillis, long maxSilenceMillis, int minAnswers, double activationScore,
            double fullEffectScore, double maxScore, double maxDamageReduction) {}

    public ChecksConfigManager(Plugin plugin) {
        super(plugin);
        initialize();
    }

    @Override
    public void loadConfigs() {
        addCustomConfig(new CustomConfig("checks.yml", plugin));
    }

    @Override
    public void loadValues() {
        CustomConfig config = getChecksConfig();
        requireSection(config, "analyze");
        requireSection(config, "analyze.models");
        requireSection(config, "analyze.buffer");
        requireSection(config, "analyze.buffer.weights");
        requireSection(config, "analyze.mitigation");
        analysisEnabled = config.getBoolean("analyze.enabled", false);
        primaryModel = ModelId.parse(config.getRawString("analyze.primary", "pro"));
        Map<ModelId, AnalysisSettings> loaded = new EnumMap<>(ModelId.class);
        Map<ModelId, Double> weights = new EnumMap<>(ModelId.class);
        for (ModelId model : ModelId.values()) {
            String path = "analyze.models." + model.key();
            requireSection(config, path);
            requireSection(config, path + ".actions");
            String inherited = config.getRawString(path + ".inherit-buffer", "").trim();
            ModelId owner = inherited.isEmpty() ? model : ModelId.parse(inherited);
            if (!inherited.isEmpty() && owner == model)
                throw new IllegalArgumentException(path + ".inherit-buffer cannot refer to itself");
            loaded.put(model, new AnalysisSettings(
                    config.getBoolean(path + ".enabled", true),
                    config.requireInt(path + ".step", model.step(), 1, model.sequence()),
                    config.requireInt(path + ".combat", model == ModelId.PRO ? 50 : 64, 0, 72000) * 50L,
                    config.requireDouble(path + ".probability", .85, 0, 1),
                    new ModelActions(config.getBoolean(path + ".actions.alert", true),
                            config.getBoolean(path + ".actions.mitigate", true),
                            config.getBoolean(path + ".actions.punish", true)),
                    config.requireDouble(path + ".mitigation-weight", 1, 0, Double.MAX_VALUE), owner));
            weights.put(model, config.requireDouble(
                    "analyze.buffer.weights." + model.key(), 1, 0, Double.MAX_VALUE));
        }
        for (var settings : loaded.values()) {
            ModelId owner = settings.bufferOwner();
            if (loaded.get(owner).bufferOwner() != owner)
                throw new IllegalArgumentException("Buffer inheritance chains and cycles are not supported");
        }
        modelSettings = Map.copyOf(loaded);
        double flag = config.requireDouble("analyze.buffer.flag", 50, .001, 1_000_000);
        bufferSettings = new BufferSettings(flag,
                config.requireDouble("analyze.buffer.reset_on_flag", 25, 0, flag),
                config.requireDouble("analyze.buffer.multiplier", 100, 0, 1_000_000),
                config.requireDouble("analyze.buffer.decrease", .25, 0, 1_000_000),
                config.requireDouble("analyze.buffer.primary-multiplier", 2, 1, Double.MAX_VALUE),
                Map.copyOf(weights));

        String path = "analyze.mitigation";
        long halfLife = config.getLong(path + ".half-life-seconds", 30);
        long silence = config.getLong(path + ".max-silence-seconds", 10);
        if (halfLife <= 0 || silence <= 0
                || halfLife > Long.MAX_VALUE / 1000 || silence > Long.MAX_VALUE / 1000)
            throw new IllegalArgumentException("Mitigation durations must be positive milliseconds-safe seconds");
        double neutral = config.requireDouble(path + ".neutral-probability", .5, 0, 1);
        if (neutral <= 0 || neutral >= 1)
            throw new IllegalArgumentException(path + ".neutral-probability must be between 0 and 1");
        double activation = config.requireDouble(path + ".activation-score", 3, 0, Double.MAX_VALUE);
        double full = config.requireDouble(path + ".full-effect-score", 10, 0, Double.MAX_VALUE);
        double max = config.requireDouble(path + ".max-score", 20, 0, Double.MAX_VALUE);
        if (full <= activation || max < full)
            throw new IllegalArgumentException("Mitigation requires activation-score < full-effect-score <= max-score");
        mitigationSettings = new MitigationSettings(config.getBoolean(path + ".enabled", false),
                neutral, halfLife * 1000, silence * 1000,
                config.requireInt(path + ".min-answers", 3, 1, Integer.MAX_VALUE),
                activation, full, max,
                config.requireDouble(path + ".max-damage-reduction", .5, 0, 1));

        apiKey = config.getRawString("analyze.api-key", "").trim();
        analyzeServer = config.getRawString("analyze.server", "https://api.raveon.wtf/v1/inference");
        java.net.URI endpoint = java.net.URI.create(analyzeServer);
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Invalid analyze.server URL");
        worldGuardRegionBypassService = new WorldGuardRegionBypassService(plugin,
                WorldGuardRegionBypassConfig.fromConfig(config, "analyze.worldguard"));
    }

    /** Stores the key issued by /raveon connect and turns analysis on. */
    public static void saveConnection(Plugin plugin, String apiKey) {
        CustomConfig checks = new CustomConfig("checks.yml", plugin);
        checks.getConfig().set("analyze.api-key", apiKey);
        checks.getConfig().set("analyze.enabled", true);
        checks.save();
    }

    private void requireSection(CustomConfig config, String path) {
        if (config.getConfig().contains(path) && !config.getConfig().isConfigurationSection(path))
            throw new IllegalArgumentException(path + " must be a section");
    }

    public boolean isModelEnabled(ModelId model) {
        return analysisEnabled && modelSettings.get(model).enabled();
    }

    public int getAnalysisSequence() {
        return getAnalysisSequence(ModelId.PRO);
    }

    public int getAnalysisSequence(ModelId model) {
        return model.sequence();
    }

    public int getAnalysisStep() {
        return getAnalysisStep(ModelId.PRO);
    }

    public int getAnalysisStep(ModelId model) {
        return modelSettings.get(model).step();
    }

    public long getCombatTimer() {
        return getCombatTimer(ModelId.PRO);
    }

    public long getCombatTimer(ModelId model) {
        return modelSettings.get(model).combatMillis();
    }

    public CustomConfig getChecksConfig() {
        return getCustomConfig("checks.yml");
    }

    public boolean isAimAiBypassedInRegion(Player player) {
        return worldGuardRegionBypassService != null && worldGuardRegionBypassService.isBypassed(player);
    }
}
