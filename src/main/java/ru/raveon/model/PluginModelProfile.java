package ru.raveon.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Map;

/** An immutable API snapshot. Capture schemas and model transport IDs are independent. */
public record PluginModelProfile(ModelId primary, Map<ModelId, Model> models, String crc, CloudPunishments punishments) {
    public static final int MAX_WINDOW = 600;
    public static final PluginModelProfile EMPTY = new PluginModelProfile(null, Map.of(), "", CloudPunishments.OFF);

    public record Model(String id, String name, String shortTitle, int window, int step, ModelPolicy policy, int wireId, String capture) {}

    public PluginModelProfile {
        models = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(models));
        primary = primary == null ? null : models.keySet().stream().filter(primary::equals).findFirst().orElse(primary);
        if ((models.isEmpty() && primary != null) || (!models.isEmpty() && (primary == null || !models.containsKey(primary))))
            throw new IllegalArgumentException("Primary must name an available model");
    }

    public static PluginModelProfile parse(JsonObject node) {
        if (integer(node, "version") != 1 || !node.has("models") || !node.get("models").isJsonArray())
            throw new IllegalArgumentException("Unsupported model profile");
        Map<ModelId, Model> models = new java.util.LinkedHashMap<>();
        if (node.getAsJsonArray("models").size() > ModelId.MAX_MODELS) throw new IllegalArgumentException("Too many models");
        java.util.Set<Integer> wires = new java.util.HashSet<>();
        for (JsonElement element : node.getAsJsonArray("models")) {
            JsonObject entry = element.getAsJsonObject();
            String id = text(entry, "id", 64);
            String capture = entry.has("capture") ? text(entry, "capture", 16) : id;
            ModelId model = ModelId.define(id, integer(entry, "wire_id"), capture);
            if (!wires.add(model.wireId())) throw new IllegalArgumentException("Duplicate wire ID");
            int window = integer(entry, "window");
            int step = integer(entry, "step");
            if (window < model.sequence() || window > MAX_WINDOW || step < 1 || step > window)
                throw new IllegalArgumentException("Invalid window/step for " + id);
            Model spec = new Model(id, text(entry, "name", 80), text(entry, "short_title", 32), window, step, ModelPolicy.parse(entry, model), model.wireId(), model.capture());
            if (models.put(model, spec) != null) throw new IllegalArgumentException("Duplicate model: " + id);
        }
        for (var model : models.entrySet()) {
            ModelId owner = model.getValue().policy() == null ? null : model.getValue().policy().buffer().inherit();
            if (owner != null && (!models.containsKey(owner) || (models.get(owner).policy() != null && models.get(owner).policy().buffer().inherit() != null)))
                throw new IllegalArgumentException("Unknown buffer owner or inheritance chain");
        }
        JsonElement primary = node.get("primary");
        ModelId lead = primary == null || primary.isJsonNull() ? null : ModelId.parse(text(node, "primary", 64));
        String crc = node.has("crc") ? text(node, "crc", 8) : "";
        if (!crc.isEmpty() && !crc.matches("[0-9a-f]{8}")) throw new IllegalArgumentException("Invalid profile CRC");
        CloudPunishments cloud = node.has("punishments") ? CloudPunishments.parse(node.getAsJsonObject("punishments")) : CloudPunishments.OFF;
        return new PluginModelProfile(lead, models, crc, cloud);
    }

    private static int integer(JsonObject node, String field) {
        JsonElement value = node.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid " + field);
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Invalid " + field, error);
        }
    }

    private static String text(JsonObject node, String field, int max) {
        JsonElement value = node.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Invalid " + field);
        String text = value.getAsString().trim();
        if (text.isEmpty() || text.length() > max || text.indexOf('\u00a7') >= 0
                || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + field);
        return text;
    }
}
