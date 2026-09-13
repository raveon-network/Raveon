package ru.raveon.manager.anticheat;

import org.bukkit.Bukkit;
import ru.raveon.Raveon;
import ru.raveon.checks.Check;
import ru.raveon.config.anticheat.PunishmentConfigManager;
import ru.raveon.utils.SchedulerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class PunishmentManager {
    private final Raveon plugin;
    private final PunishmentConfigManager configManager;
    private final List<ActivePunishGroup> groups = new ArrayList<>();

    public PunishmentManager(Raveon plugin, PunishmentConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        reload();
    }

    public void reload() {
        groups.clear();

        for (PunishmentConfigManager.PunishGroupConfig config : configManager.getPunishGroups()) {
            groups.add(new ActivePunishGroup(
                    config.name(),
                    config.checks(),
                    config.commands().stream().map(ParsedRuntimeCommand::new).toList(),
                    config.removeViolationsAfterMillis()
            ));
        }
    }

    public void handleViolation(Check check, String verbose) {
        handleViolation(check, verbose, -1.0D);
    }

    public void handleViolation(Check check, String verbose, double aiProbability) {
        String normalizedCheckName = normalize(check.getCheckName());

        for (ActivePunishGroup group : groups) {
            if (!group.matches(normalizedCheckName)) {
                continue;
            }

            long now = System.currentTimeMillis();
            group.cleanup(now);
            group.addViolation(now, normalizedCheckName);

            int totalViolations = group.getTotalViolations();
            int checkViolations = group.getViolationsForCheck(normalizedCheckName);

            for (ParsedRuntimeCommand command : group.commands()) {
                if (totalViolations < command.threshold()) {
                    continue;
                }

                if (!shouldExecute(totalViolations, command.threshold(), command.interval())) {
                    continue;
                }

                executeAction(command.command(), check, verbose, aiProbability, checkViolations, totalViolations);
            }
        }
    }

    private boolean shouldExecute(int totalViolations, int threshold, int interval) {
        if (interval <= 0) {
            return totalViolations == threshold;
        }

        if (totalViolations == threshold) {
            return true;
        }

        return totalViolations > threshold && (totalViolations - threshold) % interval == 0;
    }

    private void executeAction(String action, Check check, String verbose, double aiProbability, int checkViolations, int totalViolations) {
        String trimmedAction = action.trim();

        switch (trimmedAction.toLowerCase(Locale.ROOT)) {
            case "[alert]" -> executeAlert(check, verbose);
            case "[log]" -> executeLog(check, verbose);
            case "[ai_alert]" -> executeAiAlert(check, aiProbability);
            default -> executeConsoleCommand(trimmedAction, check, verbose, aiProbability, checkViolations, totalViolations);
        }
    }

    public void executeAlert(Check check, String verbose) {
        String alertMessage = Raveon.INSTANCE.getMainConfigManager().getAlertMessage()
                .replace("{check_name}", check.getCheckName())
                .replace("{player}", check.getPlayer().getName())
                .replace("{verbose}", verbose)
                .replace("{vl}", String.valueOf((int) check.getViolations()));

        Raveon.INSTANCE.getAlertManager().sendAlert(alertMessage);
    }

    private void executeLog(Check check, String verbose) {
        Raveon.INSTANCE.getViolationManager().logAlert(check, verbose);
    }

    private void executeAiAlert(Check check, double aiProbability) {
        if (aiProbability < 0.0D) {
            return;
        }

        String probabilityString = Raveon.INSTANCE.getMainConfigManager().getChanceString(aiProbability);

        String message = Raveon.INSTANCE.getMainConfigManager().getAiAlertMessage()
                .replace("{player}", check.getPlayer().getName())
                .replace("{vl}", String.valueOf((int) check.getViolations()))
                .replace("{probability}", probabilityString);

        Raveon.INSTANCE.getAlertManager().sendAlert(message);
    }

    private void executeConsoleCommand(String commandTemplate, Check check, String verbose, double aiProbability, int checkViolations, int totalViolations) {
        String command = applyPlaceholders(commandTemplate, check, verbose, aiProbability, checkViolations, totalViolations);

        SchedulerUtils.run(plugin, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
        );
    }

    private String applyPlaceholders(String template, Check check, String verbose, double aiProbability, int checkViolations, int totalViolations) {
        String probabilityString = aiProbability < 0.0D
                ? "0.0000"
                : Raveon.INSTANCE.getMainConfigManager().getChanceString(aiProbability);

        return template
                .replace("{player}", check.getPlayer().getName())
                .replace("{check}", check.getCheckName())
                .replace("{check_name}", check.getCheckName())
                .replace("{verbose}", verbose)
                .replace("{vl}", String.valueOf((int) check.getViolations()))
                .replace("{violations}", String.valueOf(checkViolations))
                .replace("{total_violations}", String.valueOf(totalViolations))
                .replace("{probability}", probabilityString);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static final class ActivePunishGroup {
        private final String name;
        private final List<String> checkPatterns;
        private final List<ParsedRuntimeCommand> commands;
        private final long removeViolationsAfterMillis;
        private final NavigableMap<Long, String> violations = new ConcurrentSkipListMap<>();

        private ActivePunishGroup(String name, List<String> checkPatterns, List<ParsedRuntimeCommand> commands, long removeViolationsAfterMillis) {
            this.name = name;
            this.checkPatterns = checkPatterns;
            this.commands = commands;
            this.removeViolationsAfterMillis = removeViolationsAfterMillis;
        }

        public boolean matches(String checkName) {
            List<String> includes = new ArrayList<>();
            List<String> excludes = new ArrayList<>();

            for (String pattern : checkPatterns) {
                if (pattern.startsWith("!")) {
                    excludes.add(pattern.substring(1));
                    continue;
                }

                includes.add(pattern);
            }

            boolean included = includes.isEmpty() || includes.stream().anyMatch(checkName::contains);
            boolean excluded = excludes.stream().anyMatch(checkName::contains);

            return included && !excluded;
        }

        public void addViolation(long timestamp, String checkName) {
            while (violations.containsKey(timestamp)) {
                timestamp++;
            }
            violations.put(timestamp, checkName);
        }

        public void cleanup(long now) {
            long minTimestamp = now - removeViolationsAfterMillis;
            violations.headMap(minTimestamp, false).clear();
        }

        public int getTotalViolations() {
            return violations.size();
        }

        public int getViolationsForCheck(String checkName) {
            return (int) violations.values().stream()
                    .filter(checkName::equals)
                    .count();
        }

        public List<ParsedRuntimeCommand> commands() {
            return commands;
        }

        public String name() {
            return name;
        }
    }

    private static final class ParsedRuntimeCommand {
        private final int threshold;
        private final int interval;
        private final String command;

        private ParsedRuntimeCommand(PunishmentConfigManager.ParsedPunishmentCommand config) {
            this.threshold = config.threshold();
            this.interval = config.interval();
            this.command = config.command();
        }

        public int threshold() {
            return threshold;
        }

        public int interval() {
            return interval;
        }

        public String command() {
            return command;
        }
    }
}
