package ru.raveon.service.analyze;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.raveon.model.ModelId;
import ru.raveon.model.PluginModelProfile;
import ru.raveon.utils.math.RotationRingBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Executable cross-model contract checks, run by Gradle check. */
public final class ModelProfileContractTest {
    private static final String PROFILE = """
        {"version":1,"primary":"ultra","models":[
          {"id":"pro","wire_id":1,"name":"Custom Pro","short_title":"P","window":80,"step":20},
          {"id":"ultra","wire_id":2,"name":"Custom Ultra","short_title":"U","window":96,"step":16}]}
        """;

    public static void main(String[] args) throws Exception {
        PluginModelProfile profile = PluginModelProfile.parse(json(PROFILE));
        require(profile.primary() == ModelId.ULTRA, "Remote primary");
        var ultra = profile.models().get(ModelId.ULTRA);
        require(ultra.id().equals("ultra") && ultra.name().equals("Custom Ultra")
                && ultra.shortTitle().equals("U") && ultra.window() == 96 && ultra.step() == 16, "Remote metadata");
        for (String invalid : List.of(
                PROFILE.replace("\"wire_id\":2", "\"wire_id\":1"),
                PROFILE.replace("\"window\":96", "\"window\":63"),
                PROFILE.replace("\"window\":96", "\"window\":601"),
                PROFILE.replace("\"step\":16", "\"step\":97"),
                PROFILE.replace("\"step\":16", "\"step\":1.5"),
                PROFILE.replace("\"primary\":\"ultra\"", "\"primary\":null"),
                PROFILE.replace("\"id\":\"ultra\"", "\"id\":\"pro\""),
                PROFILE.replace("Custom Ultra", "Bad\\nName"),
                PROFILE.replace("\"version\":1", "\"version\":2"))) {
            boolean rejected = false;
            try { PluginModelProfile.parse(json(invalid)); } catch (RuntimeException expected) { rejected = true; }
            require(rejected, "Invalid profile rejected");
        }
        require(PluginModelProfile.parse(json("{\"version\":1,\"primary\":null,\"models\":[]}")).models().isEmpty(), "All models disabled");
        String reply = "{\"server\":\"test\",\"models\":[\"pro\",\"ultra\"],\"profile\":" + PROFILE + "}";
        require(AnalyzeConnection.parse(200, reply).status() == AnalyzeConnection.Status.CONNECTED, "Complete auth profile");
        require(AnalyzeConnection.parse(200, "{\"models\":[\"pro\"]}").status() == AnalyzeConnection.Status.UNAVAILABLE, "Missing profile fails closed");
        require(AnalyzeConnection.parse(200, reply.replace("[\"pro\",\"ultra\"]", "[\"pro\"]")).status() == AnalyzeConnection.Status.UNAVAILABLE, "Access/profile mismatch");
        require(AnalyzeConnection.parse(403, "{\"detail\":\"denied\"}").status() == AnalyzeConnection.Status.REJECTED, "Access rejection");
        for (String body : List.of("404 page not found\n", "<html>Not Found</html>", "{\"detail\":\"Not Found\"}")) {
            var failure = AnalyzeConnection.parse(404, body);
            require(failure.status() == AnalyzeConnection.Status.UNAVAILABLE && failure.detail().startsWith("HTTP 404"), "Proxy errors retain HTTP status");
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.example/v1/auth/check"))
                    .header("Authorization", "Bearer secret-test-key").GET().build();
            String detail = AnalyzeConnection.describe(request, failure).detail();
            require(detail.contains("GET https://api.example/v1/auth/check") && !detail.contains("secret-test-key"), "Error identifies endpoint without credentials");
        }
        require(AnalyzeConnection.parse(401, "<html>Unauthorized</html>").status() == AnalyzeConnection.Status.REJECTED, "Non-JSON access rejection");

        RotationRingBuffer<Integer> frames = new RotationRingBuffer<>(ultra.window());
        for (int i = 0; i < 95; i++) frames.addFrame(i);
        require(frames.pollSnapshot(ultra.step()) == null, "Wait for full remote window");
        frames.addFrame(95);
        require(frames.pollSnapshot(ultra.step()).size() == 96, "Capture remote window");
        for (int i = 96; i < 111; i++) frames.addFrame(i);
        require(frames.pollSnapshot(ultra.step()) == null, "Respect remote step");
        frames.addFrame(111);
        require(frames.pollSnapshot(ultra.step()).get(0) == 16, "Advance remote step");
        byte[] typed = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[]{'G','A','I','B'}).putShort((short) 2).putDouble(.2).putDouble(.9)
                .put(new byte[]{'R','V','M','2'}).putShort((short) 2).put(new byte[]{1,0,2,0}).array();
        require(ModelWire.decode(typed, List.of(ModelId.PRO, ModelId.ULTRA))[1].probability() == .9, "Typed result association");
        boolean mismatch = false;
        try { ModelWire.decode(typed, List.of(ModelId.ULTRA, ModelId.PRO)); }
        catch (IllegalArgumentException expected) { mismatch = true; }
        require(mismatch, "Wrong-model reply rejected");
        RuntimeFeaturesContractTest.run();
        System.out.println("Model profile, capture cadence and result association checks passed");
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
