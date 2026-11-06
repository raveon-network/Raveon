package ru.raveon.config.anticheat;

import lombok.Getter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import ru.raveon.api.configuration.ConfigManager;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.model.ModelId;
import ru.raveon.model.CloudPunishments;
import ru.raveon.service.LanguageService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

@Getter
public final class PunishmentConfigManager extends ConfigManager {
    private List<PunishGroupConfig> punishGroups = List.of();
    private final LanguageService languageService;
    private final CloudPunishments cloud;
    @Getter private CloudPunishments.Policy localPolicy;

    public PunishmentConfigManager(Plugin plugin, LanguageService languageService) {
        this(plugin, languageService, CloudPunishments.OFF);
    }

    public PunishmentConfigManager(Plugin plugin, LanguageService languageService, CloudPunishments cloud) {
        super(plugin);
        this.languageService = languageService;
        this.cloud = cloud;
        initialize();
    }

    @Override
    public void loadConfigs() {
        addCustomConfig(new CustomConfig("features/punishments.yml", plugin));
    }

    @Override
    public void loadValues() {
        List<PunishGroupConfig> loaded = new ArrayList<>();
        ConfigurationSection root = languageService.localize(
                getCustomConfig("features/punishments.yml").getConfig());
        for (String groupName : root.getKeys(false)) {
            if (!groupName.matches("[a-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Invalid punishment group name: " + groupName);
            ConfigurationSection section = root.getConfigurationSection(groupName);
            if (section == null) throw new IllegalArgumentException(groupName + " must be a section");
            List<String> checks = strings(section, "checks").stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).toList();
            Set<ModelId> models = EnumSet.noneOf(ModelId.class);
            if (!section.contains("models")) models.addAll(EnumSet.allOf(ModelId.class));
            else for (String model : strings(section, "models")) models.add(ModelId.parse(model));
            List<ParsedPunishmentCommand> commands =
                    strings(section, "commands").stream().map(this::parseCommand).toList();
            Object duration = section.get("remove-violations-after", 300);
            if (!(duration instanceof Integer || duration instanceof Long))
                throw new IllegalArgumentException(groupName + ".remove-violations-after must be an integer");
            long seconds = ((Number) duration).longValue();
            if (seconds < 1 || seconds > 604800)
                throw new IllegalArgumentException("remove-violations-after must be in 1..604800");
            NavigableMap<Integer, List<String>> stages = new TreeMap<>();
            ConfigurationSection stageSection = section.getConfigurationSection("stages");
            if (section.contains("stages") && stageSection == null)
                throw new IllegalArgumentException(groupName + ".stages must be a section");
            if (stageSection != null) {
                for (String key : stageSection.getKeys(false)) {
                    int threshold = Integer.parseInt(key);
                    if (threshold <= 0 || stages.containsKey(threshold))
                        throw new IllegalArgumentException("Invalid or duplicate punishment stage: " + key);
                    stages.put(threshold, strings(stageSection, key));
                }
            }
            loaded.add(new PunishGroupConfig(groupName, checks, Set.copyOf(models), commands,
                    Collections.unmodifiableNavigableMap(stages), seconds * 1000));
        }
        localPolicy = new CloudPunishments.Policy(loaded.stream().map(g -> new CloudPunishments.Group(
                g.name(), g.models(), g.checks(), (int)(g.removeViolationsAfterMillis() / 1000),
                g.commands().stream().map(c -> c.threshold() + ":" + c.interval() + " " + c.command()).toList(), g.stages())).toList());
        CloudPunishments.parsePolicy(localPolicy.json());
        if (cloud.mode().equals("sync") && cloud.policy() != null) {
            loaded.clear();
            for (var g : cloud.policy().groups()) loaded.add(new PunishGroupConfig(g.name(),
                    g.checks().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList(), g.models(),
                    g.commands().stream().map(this::parseCommand).toList(),
                    Collections.unmodifiableNavigableMap(new TreeMap<>(g.stages())), g.seconds() * 1000L));
        }
        punishGroups = List.copyOf(loaded);
    }

    private List<String> strings(ConfigurationSection section, String path) {
        Object value = section.get(path);
        if (value == null) return List.of();
        if (!(value instanceof List<?> values))
            throw new IllegalArgumentException(section.getCurrentPath() + "." + path + " must be a list");
        List<String> result = new ArrayList<>();
        for (Object entry : values) {
            if (!(entry instanceof String text) || text.isBlank())
                throw new IllegalArgumentException(section.getCurrentPath() + "." + path + " must contain nonempty strings");
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private ParsedPunishmentCommand parseCommand(String line) {
        int firstColon = line.indexOf(':');
        int firstSpace = line.indexOf(' ');
        if (firstColon <= 0 || firstSpace <= firstColon + 1)
            throw new IllegalArgumentException("Invalid punishment command format: " + line);
        int threshold = Integer.parseInt(line.substring(0, firstColon).trim());
        int interval = Integer.parseInt(line.substring(firstColon + 1, firstSpace).trim());
        String command = line.substring(firstSpace + 1).trim();
        if (threshold <= 0 || interval < 0 || command.isBlank())
            throw new IllegalArgumentException("Invalid punishment threshold, interval or command: " + line);
        return new ParsedPunishmentCommand(threshold, interval, command);
    }

    public record PunishGroupConfig(
            String name, List<String> checks, Set<ModelId> models,
            List<ParsedPunishmentCommand> commands, NavigableMap<Integer, List<String>> stages,
            long removeViolationsAfterMillis) {

        public boolean matches(String checkName, ModelId model) {
            if (!models.contains(model)) return false;
            String normalized = checkName.toLowerCase(Locale.ROOT);
            boolean included = checks.stream().noneMatch(pattern -> !pattern.startsWith("!"));
            for (String pattern : checks) {
                if (pattern.startsWith("!")) {
                    if (normalized.contains(pattern.substring(1))) return false;
                } else if (normalized.contains(pattern)) included = true;
            }
            return included;
        }

        public List<String> actions(long violations) {
            List<String> actions = new ArrayList<>();
            for (ParsedPunishmentCommand command : commands) {
                long offset = violations - command.threshold();
                if (offset == 0 || offset > 0 && command.interval() > 0 && offset % command.interval() == 0)
                    actions.add(command.command());
            }
            var stage = stages.floorEntry((int) Math.min(Integer.MAX_VALUE, violations));
            if (stage != null) actions.addAll(stage.getValue());
            return List.copyOf(actions);
        }
    }

    public record ParsedPunishmentCommand(int threshold, int interval, String command) {}

    public static void writeCloudPolicy(Plugin plugin, CloudPunishments.Policy policy) {
        var file = new java.io.File(plugin.getDataFolder(), "features/punishments.yml").toPath();
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        for (var group : policy.groups()) {
            var section = yaml.createSection(group.name());
            section.set("models", group.models().stream().sorted().map(ModelId::key).toList());
            section.set("checks", group.checks());
            section.set("remove-violations-after", group.seconds());
            section.set("commands", group.commands());
            if (!group.stages().isEmpty()) {
                var stages = section.createSection("stages");
                new TreeMap<>(group.stages()).forEach((key,value) -> stages.set(key.toString(), value));
            }
        }
        java.nio.file.Path temporary = null;
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            temporary = java.nio.file.Files.createTempFile(file.getParent(), ".punishments-", ".tmp");
            java.nio.file.Files.writeString(temporary, yaml.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
            try {
                java.nio.file.Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                java.nio.file.Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot synchronize punishments.yml", error);
        } finally {
            if (temporary != null) try { java.nio.file.Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) {}
        }
    }
}
