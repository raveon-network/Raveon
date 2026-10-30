package ru.raveon.manager.anticheat;

import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.model.ModelId;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class ModelDetectionState {
    private final Map<ModelId, Double> buffers = new EnumMap<>(ModelId.class);
    private final LongSupplier clock;
    private ChecksConfigManager settings;
    private boolean enabled;
    private volatile MitigationSnapshot mitigation = new MitigationSnapshot(0, 0, 0, false);

    public ModelDetectionState() {
        this(System::nanoTime);
    }

    public ModelDetectionState(LongSupplier clock) {
        this.clock = clock;
    }

    public void reload(ChecksConfigManager next) {
        boolean nextEnabled = next.isAnalysisEnabled();
        boolean changed = settings == null || enabled != nextEnabled
                || !settings.getModelSettings().equals(next.getModelSettings())
                || !settings.getBufferSettings().equals(next.getBufferSettings())
                || settings.getPrimaryModel() != next.getPrimaryModel();
        if (changed) buffers.clear();
        if (changed || settings.getPrimaryModel() != next.getPrimaryModel()
                || !settings.getMitigationSettings().equals(next.getMitigationSettings()))
            mitigation = new MitigationSnapshot(0, 0, 0, false);
        settings = next;
        enabled = nextEnabled;
    }

    public Result accept(ModelId source, double probability) {
        if (!enabled || !settings.isModelEnabled(source) || !Double.isFinite(probability))
            return null;
        probability = Math.max(0, Math.min(1, probability));
        var modelSettings = settings.getModelSettings().get(source);
        var config = settings.getBufferSettings();
        double weight = config.weights().get(source);
        if (source == settings.getPrimaryModel())
            weight = Math.min(Double.MAX_VALUE, weight * config.primaryMultiplier());
        ModelId owner = modelSettings.bufferOwner();
        double before = buffers.getOrDefault(owner, 0.0);
        double after = before;
        if (weight > 0) {
            if (probability > modelSettings.probability()) {
                double increase = (probability - modelSettings.probability()) * config.multiplier();
                after = Math.min(Double.MAX_VALUE, before + increase * weight);
            } else if (probability < .1) {
                double decrease = Math.min(Double.MAX_VALUE, config.decrease() * weight);
                after = Math.max(0, before - decrease);
            }
        }
        boolean crossed = after > config.flag() && weight > 0;
        buffers.put(owner, crossed ? config.reset() : after);
        recordMitigation(source, probability);
        return new Result(source, owner, probability, after, crossed, modelSettings.actions());
    }

    public double buffer(ModelId model) {
        return buffers.getOrDefault(settings.getModelSettings().get(model).bufferOwner(), 0.0);
    }

    private void recordMitigation(ModelId source, double probability) {
        var config = settings.getMitigationSettings();
        var policy = settings.getModelSettings().get(source);
        if (!config.enabled() || !policy.actions().mitigate() || policy.mitigationWeight() == 0) return;
        double maxWeight = 0;
        ModelId lead = null;
        for (ModelId model : ModelId.values()) {
            var candidate = settings.getModelSettings().get(model);
            if (!settings.isModelEnabled(model) || !candidate.actions().mitigate()
                    || candidate.mitigationWeight() == 0) continue;
            maxWeight = Math.max(maxWeight, candidate.mitigationWeight());
            if (lead == null || model == settings.getPrimaryModel()) lead = model;
        }
        if (maxWeight == 0) return;
        double sum = 0;
        for (ModelId model : ModelId.values()) {
            var candidate = settings.getModelSettings().get(model);
            if (settings.isModelEnabled(model) && candidate.actions().mitigate())
                sum += candidate.mitigationWeight() / maxWeight;
        }
        double share = (policy.mitigationWeight() / maxWeight) / sum;
        double cadence = Math.min(1.0, (double) settings.getAnalysisStep(source)
                / settings.getAnalysisSequence(settings.getPrimaryModel()));
        double p = Math.max(.001, Math.min(.999, probability));
        double contribution = share * cadence
                * (Math.log(p / (1 - p)) - Math.log(config.neutralProbability() / (1 - config.neutralProbability())));
        long now = clock.getAsLong();
        MitigationSnapshot previous = mitigation;
        long elapsed = elapsedMillis(now, previous.updatedAt());
        boolean fresh = previous.sampled() && elapsed < config.maxSilenceMillis();
        double score = fresh
                ? previous.score() * Math.pow(.5, (double) elapsed / config.halfLifeMillis()) : 0;
        int answers = fresh ? previous.answers() : 0;
        if (source == lead && answers < Integer.MAX_VALUE) answers++;
        mitigation = new MitigationSnapshot(
                Math.max(0, Math.min(config.maxScore(), score + contribution)),
                now, answers, true);
    }

    public double damageMultiplier(ChecksConfigManager current) {
        return damageMultiplier(current, clock.getAsLong());
    }

    private double damageMultiplier(ChecksConfigManager current, long now) {
        var config = current.getMitigationSettings();
        if (settings != current || !enabled || !config.enabled()) return 1;
        MitigationSnapshot snapshot = mitigation;
        long elapsed = elapsedMillis(now, snapshot.updatedAt());
        if (snapshot.answers() < config.minAnswers() || elapsed >= config.maxSilenceMillis()) return 1;
        double score = snapshot.score() * Math.pow(.5, (double) elapsed / config.halfLifeMillis());
        double effect = Math.max(0, Math.min(1,
                (score - config.activationScore()) / (config.fullEffectScore() - config.activationScore())));
        return 1 - effect * config.maxDamageReduction();
    }

    public double mitigationScore() {
        return mitigation.score();
    }

    private static long elapsedMillis(long now, long before) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(now - before));
    }

    private record MitigationSnapshot(double score, long updatedAt, int answers, boolean sampled) {}

    public record Result(ModelId model, ModelId bufferOwner, double probability,
            double atResult, boolean crossed, ChecksConfigManager.ModelActions actions) {}
}
