package ru.raveon.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record ModelPolicy(boolean alert, boolean mitigate, boolean punish, double mitigationWeight, Buffer buffer) {
    public record Buffer(double flag, double reset, double multiplier, double decrease, double weight,
            double primaryMultiplier, double threshold, double legitThreshold, ModelId inherit) {}

    public static ModelPolicy parse(JsonObject entry, ModelId model) {
        if (!entry.has("buffer") && !entry.has("roles")) return null; // Earlier additive profile contract.
        JsonObject roles = entry.getAsJsonObject("roles");
        JsonObject b = entry.getAsJsonObject("buffer");
        double flag = number(b, "flag", .001, 1_000_000);
        double threshold = number(b, "threshold", 0, 1);
        ModelId inherit = b.get("inherit") == null || b.get("inherit").isJsonNull()
                ? null : ModelId.parse(b.get("inherit").getAsString());
        if (inherit == model) throw new IllegalArgumentException("Self-inheriting buffer");
        return new ModelPolicy(bool(roles, "alert"), bool(roles, "mitigate"), bool(roles, "punish"),
                number(entry, "mitigation_weight", 0, 1000), new Buffer(flag,
                number(b, "reset", 0, flag), number(b, "multiplier", 0, 1_000_000),
                number(b, "decrease", 0, 1_000_000), number(b, "weight", 0, 1000),
                number(b, "primary_multiplier", 1, 1000), threshold,
                number(b, "legit_threshold", 0, threshold), inherit));
    }

    private static boolean bool(JsonObject node, String field) {
        JsonElement value = node.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Invalid role: " + field);
        return value.getAsBoolean();
    }

    private static double number(JsonObject node, String field, double min, double max) {
        JsonElement value = node.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid policy: " + field);
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || result < min || result > max)
            throw new IllegalArgumentException("Out of range: " + field);
        return result;
    }
}
