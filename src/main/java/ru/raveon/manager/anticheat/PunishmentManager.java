package ru.raveon.manager.anticheat;

import org.bukkit.Bukkit;

import ru.raveon.Raveon;
import ru.raveon.checks.Check;
import ru.raveon.checks.impl.ai.AimAI;
import ru.raveon.config.RuntimeConfiguration;
import ru.raveon.config.anticheat.ChecksConfigManager.ModelActions;
import ru.raveon.database.modules.PunishmentDatabase;
import ru.raveon.model.ModelId;
import ru.raveon.service.LanguageService;
import ru.raveon.utils.ProbabilityUtils;
import ru.raveon.utils.SchedulerUtils;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PunishmentManager {
    private final Raveon plugin;
    private final PunishmentDatabase database;

    public PunishmentManager(Raveon plugin, PunishmentDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    public CompletableFuture<Void> handleFlag(AimAI check, ModelDetectionState.Result result, String verbose) {
        RuntimeConfiguration runtime = plugin.getConfiguration();
        if (check.getSettings() != runtime.checks())
            return CompletableFuture.completedFuture(null);
        return handle(snapshot(check, result.model(), result.probability(),
                result.atResult(), verbose), result.actions(), runtime);
    }

    private CompletableFuture<Void> handle(Flag flag, ModelActions roles, RuntimeConfiguration runtime) {
        if (!roles.punish()) {
            if (roles.alert()) sendAlert(flag, runtime.main().getLanguageService(), "",
                    flag.checkVl(), flag.checkVl());
            return CompletableFuture.completedFuture(null);
        }
        var groups = runtime.punishments().getPunishGroups().stream()
                .filter(group -> group.matches(flag.check(), flag.model())).toList();
        long submittedAt = System.nanoTime();
        CompletableFuture<Void> result = database.record(
                flag.uuid(), flag.model(), flag.check(), groups, System.currentTimeMillis())
                .thenAccept(counts -> {
                    if (counts.isEmpty() || !plugin.isEnabled()) return;
                    SchedulerUtils.run(plugin, () -> {
                        if (!plugin.isEnabled() || plugin.getConfiguration() != runtime
                                || System.nanoTime() - submittedAt > TimeUnit.SECONDS.toNanos(10)) return;
                        for (var counted : counts) {
                            for (String action : counted.group().actions(counted.total())) {
                                try {
                                    execute(action, flag, roles, runtime.main().getLanguageService(),
                                            counted.group().name(), counted.checkViolations(), counted.total());
                                } catch (RuntimeException error) {
                                    runtime.main().getDatabaseManager().logFailure(Level.WARNING,
                                            "Punishment action failed in group " + counted.group().name(), error);
                                }
                            }
                        }
                    });
                });
        result.whenComplete((ignored, error) -> {
            if (error != null) runtime.main().getDatabaseManager().logFailure(Level.SEVERE,
                    "Punishment processing failed for " + flag.uuid(), error);
        });
        return result;
    }

    private void execute(String action, Flag flag, ModelActions roles, LanguageService language,
            String group, long checkViolations, long total) {
        switch (action.toLowerCase(Locale.ROOT)) {
            case "[alert]", "[ai_alert]" -> {
                if (roles.alert()) sendAlert(flag, language, group, checkViolations, total);
            }
            case "[log]" -> plugin.getViolationManager().getViolationStorage().logAlert(
                    flag.uuid(), plugin.getDescription().getVersion(),
                    "group: " + group + ", " + flag.verbose(), flag.check(),
                    (int) Math.min(Integer.MAX_VALUE, total));
            default -> {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        format(action, flag, group, checkViolations, total)))
                    throw new IllegalStateException("Punishment command was not handled by the server");
            }
        }
    }

    public void executeAlert(Check check, String verbose) {
        ModelId model = check instanceof AimAI ai ? ai.getModel() : ModelId.PRO;
        if (!plugin.getChecksConfigManager().getModelSettings().get(model).actions().alert()) return;
        double probability = check instanceof AimAI ai ? ai.getLastProbability() : -1;
        double buffer = check instanceof AimAI ai ? ai.getBuffer() : 0;
        Flag flag = snapshot(check, model, probability, buffer, verbose);
        sendAlert(flag, plugin.getLanguageService(), "", flag.checkVl(), flag.checkVl());
    }

    private void sendAlert(Flag flag, LanguageService language, String group, long checkVl, long total) {
        plugin.getAlertManager().sendAlert(format(language.text("ai-alert"), flag, group, checkVl, total));
    }

    private Flag snapshot(Check check, ModelId model,
            double probability, double buffer, String verbose) {
        return new Flag(check.getPlayer().getUuid(), check.getPlayer().getName(),
                check.getCheckName(), (long) check.getViolations(), model,
                probability, buffer, verbose);
    }

    public static String format(String template, Flag flag, String group, long checkVl, long total) {
        String probability = flag.probability() < 0 ? "0" : ProbabilityUtils.formatPercent(flag.probability());
        return template.replace("{model}", flag.model().displayName())
                .replace("{group}", group)
                .replace("{player}", flag.name())
                .replace("{check}", flag.check())
                .replace("{check_name}", flag.check())
                .replace("{verbose}", flag.verbose())
                .replace("{vl}", Long.toString(total))
                .replace("{check_vl}", Long.toString(flag.checkVl()))
                .replace("{violations}", Long.toString(checkVl))
                .replace("{total_violations}", Long.toString(total))
                .replace("{buffer}", String.format(Locale.ROOT, "%.2f", flag.buffer()))
                .replace("{probability}", probability)
                .replace("{probability_percent}", probability)
                .replace("{probability_proc}", probability);
    }

    public record Flag(UUID uuid, String name, String check, long checkVl,
            ModelId model, double probability, double buffer, String verbose) {}
}
