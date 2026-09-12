package ru.raveon.config.anticheat;

import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Getter
public final class PunishmentConfigManager extends ConfigManager {
    private List<PunishGroupConfig> punishGroups;

    public PunishmentConfigManager(Plugin plugin) {
        super(plugin);
    }

    @Override
    public void loadConfigs() {
        punishGroups = new ArrayList<>();
        addCustomConfig(new CustomConfig("features/punishments.yml", plugin));
    }

    @Override
    public void loadValues() {
        punishGroups.clear();

        ConfigurationSection root = getCustomConfig("features/punishments.yml").getConfig();
        if (root == null) {
            return;
        }

        for (String groupName : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(groupName);
            if (section == null) {
                continue;
            }

            List<String> checks = section.getStringList("checks").stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList();

            List<ParsedPunishmentCommand> commands = section.getStringList("commands").stream()
                    .map(this::parseCommand)
                    .toList();

            long removeViolationsAfterMillis = section.getLong("remove-violations-after", 300L) * 1000L;

            punishGroups.add(new PunishGroupConfig(
                    groupName,
                    checks,
                    commands,
                    removeViolationsAfterMillis
            ));
        }
    }

    private ParsedPunishmentCommand parseCommand(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Punishment command cannot be empty");
        }

        int firstColon = line.indexOf(':');
        int firstSpace = line.indexOf(' ');

        if (firstColon <= 0 || firstSpace <= firstColon + 1) {
            throw new IllegalArgumentException("Invalid punishment command format: " + line);
        }

        int threshold = Integer.parseInt(line.substring(0, firstColon).trim());
        int interval = Integer.parseInt(line.substring(firstColon + 1, firstSpace).trim());
        String command = line.substring(firstSpace + 1).trim();

        if (threshold <= 0) {
            throw new IllegalArgumentException("Threshold must be > 0: " + line);
        }

        if (interval < 0) {
            throw new IllegalArgumentException("Interval must be >= 0: " + line);
        }

        if (command.isBlank()) {
            throw new IllegalArgumentException("Command cannot be blank: " + line);
        }

        return new ParsedPunishmentCommand(threshold, interval, command);
    }

    public record PunishGroupConfig(
            String name,
            List<String> checks,
            List<ParsedPunishmentCommand> commands,
            long removeViolationsAfterMillis
    ) {
    }

    public record ParsedPunishmentCommand(
            int threshold,
            int interval,
            String command
    ) {
    }
}