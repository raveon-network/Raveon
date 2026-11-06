package ru.raveon.model;

import com.google.gson.*;
import java.util.*;

/** Revisioned cloud document. Console commands execute only through the existing punishment manager. */
public record CloudPunishments(String mode, int revision, Policy policy) {
    public static final CloudPunishments OFF = new CloudPunishments("off", 0, null);
    public record Policy(List<Group> groups) {
        public Policy { groups = List.copyOf(groups); }
        public JsonObject json() {
            JsonObject root = new JsonObject();
            JsonArray values = new JsonArray();
            for (Group g : groups) {
                JsonObject value = new JsonObject();
                value.addProperty("name", g.name());
                JsonArray models = new JsonArray();
                g.models().stream().sorted().forEach(m -> models.add(m.key()));
                value.add("models", models);
                value.add("checks", strings(g.checks()));
                value.addProperty("remove_violations_after", g.seconds());
                value.add("commands", strings(g.commands()));
                JsonObject stages = new JsonObject();
                new TreeMap<>(g.stages()).forEach((k,v) -> stages.add(k.toString(), strings(v)));
                value.add("stages", stages);
                values.add(value);
            }
            root.add("groups", values);
            return root;
        }
    }
    public record Group(String name, Set<ModelId> models, List<String> checks, int seconds,
            List<String> commands, Map<Integer, List<String>> stages) {
        public Group {
            models = Set.copyOf(models); checks = List.copyOf(checks); commands = List.copyOf(commands);
            stages = Map.copyOf(stages);
        }
    }

    public static CloudPunishments parse(JsonObject node) {
        String mode = node.get("mode").getAsString();
        if (!Set.of("sync", "upload", "off").contains(mode)) throw new IllegalArgumentException("Invalid sync mode");
        int revision = integer(node.get("revision"));
        if (revision < 0) throw new IllegalArgumentException("Invalid punishment revision");
        return new CloudPunishments(mode, revision,
                node.get("policy") == null || node.get("policy").isJsonNull() ? null : parsePolicy(node.getAsJsonObject("policy")));
    }

    public static Policy parsePolicy(JsonObject node) {
        JsonArray array = node.getAsJsonArray("groups");
        if (array == null || array.size() > 32) throw new IllegalArgumentException("Invalid punishment groups");
        List<Group> groups = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonElement element : array) {
            JsonObject g = element.getAsJsonObject();
            String name = g.get("name").getAsString();
            if (!name.matches("[a-z0-9_-]{1,64}") || !names.add(name)) throw new IllegalArgumentException("Invalid group name");
            Set<ModelId> models = EnumSet.noneOf(ModelId.class);
            for (String model : texts(g.getAsJsonArray("models"), 2))
                if (!models.add(ModelId.parse(model))) throw new IllegalArgumentException("Duplicate model");
            int seconds = integer(g.get("remove_violations_after"));
            if (seconds < 1 || seconds > 604800) throw new IllegalArgumentException("Invalid violation lifetime");
            List<String> commands = texts(g.getAsJsonArray("commands"), 64).stream().map(command -> {
                validateCommand(command);
                int colon = command.indexOf(':'), space = command.indexOf(' ');
                return Integer.parseInt(command.substring(0, colon)) + ":" + Integer.parseInt(command.substring(colon + 1, space))
                        + " " + command.substring(space + 1).trim();
            }).toList();
            Map<Integer,List<String>> stages = new TreeMap<>();
            JsonObject raw = g.has("stages") ? g.getAsJsonObject("stages") : new JsonObject();
            if (raw.size() > 64) throw new IllegalArgumentException("Too many stages");
            raw.entrySet().forEach(e -> {
                int threshold = Integer.parseInt(e.getKey());
                if (threshold < 1 || stages.put(threshold, texts(e.getValue().getAsJsonArray(), 64)) != null)
                    throw new IllegalArgumentException("Invalid stage");
            });
            groups.add(new Group(name, models, texts(g.getAsJsonArray("checks"), 32).stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList(), seconds, commands, stages));
        }
        var policy = new Policy(groups);
        if (policy.json().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240000)
            throw new IllegalArgumentException("Punishment document exceeds byte limit");
        return policy;
    }

    public static void validateCommand(String command) {
        int space = command.indexOf(' '), colon = command.indexOf(':');
        if (colon < 1 || space < colon + 2 || space >= command.length() - 1)
            throw new IllegalArgumentException("Expected threshold:interval action");
        if (Integer.parseInt(command.substring(0, colon)) < 1 || Integer.parseInt(command.substring(colon + 1, space)) < 0)
            throw new IllegalArgumentException("Invalid command interval");
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
        return value.getAsBigDecimal().intValueExact();
    }
    private static List<String> texts(JsonArray array, int max) {
        if (array == null || array.size() > max) throw new IllegalArgumentException("Invalid rule list");
        List<String> result = new ArrayList<>();
        for (JsonElement e : array) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected rule text");
            String s = e.getAsString();
            if (s.isBlank() || s.length() > 512 || s.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid rule text");
            result.add(s.trim());
        }
        return List.copyOf(result);
    }
    private static JsonArray strings(Collection<String> values) {
        JsonArray array = new JsonArray(); values.forEach(array::add); return array;
    }
}
