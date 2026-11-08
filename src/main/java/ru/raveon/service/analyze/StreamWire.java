package ru.raveon.service.analyze;

import com.google.gson.*;
import ru.raveon.model.ModelId;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned stream payloads and strictly associated acknowledgement decoding. */
public final class StreamWire {
    private StreamWire() {}
    public record Result(long have, double probability, int status, String error) {}

    public static byte[] encode(UUID key, UUID player, ModelId model, long from, byte[] rows) {
        int rowBytes = model.features() * Float.BYTES;
        if (from < 1 || from > 9_007_199_254_740_391L || rows.length == 0
                || rows.length % rowBytes != 0 || rows.length / rowBytes > 600)
            throw new IllegalArgumentException("Invalid stream range");
        ByteBuffer out = ByteBuffer.allocate(50 + rows.length);
        out.put(new byte[]{'R','V','S','1'}).put((byte) model.wireId()).put((byte)(model.proCapture() ? 2 : 3));
        out.putLong(key.getMostSignificantBits()).putLong(key.getLeastSignificantBits());
        out.putLong(player.getMostSignificantBits()).putLong(player.getLeastSignificantBits());
        out.order(ByteOrder.LITTLE_ENDIAN).putLong(from).putInt(rows.length / rowBytes).put(rows);
        return out.array();
    }

    public static List<Result> decode(byte[] body, List<StreamWindowCursor.Request> expected, String crc) {
        JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        if (integer(root,"v") != 1 || !text(root,"profile_crc").equals(crc))
            throw new IllegalArgumentException("Stream profile mismatch");
        JsonArray values = root.getAsJsonArray("results");
        if (values == null || values.size() != expected.size()) throw new IllegalArgumentException("Stream count mismatch");
        List<Result> results = new ArrayList<>();
        for (int i=0;i<expected.size();i++) {
            var request = expected.get(i);
            var value = values.get(i).getAsJsonObject();
            long have = integer(value,"h"), end = integer(value,"end"), status = integer(value,"status");
            String error = value.has("error") ? text(value,"error") : "";
            if (!text(value,"k").equals(request.key().toString()) || integer(value,"model") != request.model().wireId()
                    || end != request.end() || have < 0 || status < 0 || status > 2
                    || (!error.isEmpty() && !error.equals("reopen")) || (error.isEmpty() && have != request.end()))
                throw new IllegalArgumentException("Stream acknowledgement association mismatch");
            JsonElement p = value.get("p");
            if (p == null || !p.isJsonPrimitive() || !p.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Invalid stream probability");
            double probability = p.getAsDouble();
            if (!Double.isFinite(probability) || probability < 0 || probability > 1)
                throw new IllegalArgumentException("Invalid stream probability");
            results.add(new Result(have, probability, (int) status, error));
        }
        return List.copyOf(results);
    }

    private static long integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid stream " + field);
        return value.getAsBigDecimal().longValueExact();
    }
    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Invalid stream " + field);
        return value.getAsString();
    }
}
