package ru.raveon.service.analyze;

import com.google.gson.*;
import java.nio.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.model.*;
import ru.raveon.config.anticheat.ModelBufferConfig;

public final class DynamicModelContractTest {
    public static void run() throws Exception {
        DynamicHistoryContractTest.run();
        JsonObject input = JsonParser.parseString("""
            {"version":1,"crc":"12345678","primary":"aim_fast","models":[
            {"id":"pro","wire_id":1,"name":"Pro","short_title":"P","window":50,"step":10},
            {"id":"ultra","wire_id":2,"name":"Ultra","short_title":"U","window":64,"step":32},
            {"id":"aim_fast","wire_id":3,"capture":"pro","name":"Aim Fast","short_title":"AF","window":60,"step":5},
            {"id":"combat_deep","wire_id":4,"capture":"ultra","name":"Combat Deep","short_title":"CD","window":96,"step":16}
            ]}
            """).getAsJsonObject();
        var profile = PluginModelProfile.parse(input);
        var fast = profile.primary();
        var deep = profile.models().keySet().stream().filter(m -> m.key().equals("combat_deep")).findFirst().orElseThrow();
        check(profile.models().size() == 4 && fast.key().equals("aim_fast") && fast.proCapture() && !deep.proCapture(), "Arbitrary model IDs and independent capture schemas");
        check(fast.wireId() == 3 && deep.wireId() == 4 && fast.features() == 8 && deep.features() == ModelId.ULTRA.features(), "Transport identity is separate from feature format");
        check(profile.models().containsKey(ModelId.parse("aim_fast")), "References resolve by stable ID");
        var reply = new JsonObject(); reply.addProperty("server", "test");
        var allowed = new JsonArray(); profile.models().keySet().forEach(m -> allowed.add(m.key()));
        reply.add("models", allowed); reply.add("profile", input);
        check(AnalyzeConnection.parse(200, reply.toString()).status() == AnalyzeConnection.Status.CONNECTED, "Connection accepts more than two API models");

        var buffers = new ModelCaptureBuffers(); buffers.configure(profile);
        RotationFrame row = new RotationFrame(0,0,0,0,0,0,0,0);
        for (int i=0;i<50;i++) buffers.addPro(row);
        check(buffers.pro(ModelId.PRO,10).size()==50 && buffers.pro(fast,5)==null, "Models sharing a collector have independent windows");
        for (int i=0;i<10;i++) buffers.addPro(row);
        check(buffers.pro(fast,5).size()==60 && buffers.pro(ModelId.PRO,10).size()==50, "Reading one model does not consume another model's frames");
        for (int i=0;i<5;i++) buffers.addPro(row);
        check(buffers.pro(fast,5).size()==60 && buffers.pro(ModelId.PRO,10)==null, "Independent API cadence");
        buffers.clear(fast);
        for (int i=0;i<5;i++) buffers.addPro(row);
        check(buffers.pro(fast,5)==null && buffers.pro(ModelId.PRO,10).size()==50, "Invalidation is isolated by model");
        var removed=input.deepCopy(); removed.addProperty("primary","pro"); removed.getAsJsonArray("models").remove(2);
        buffers.configure(PluginModelProfile.parse(removed));
        check(buffers.pro(fast,5)==null, "Profile removal drops the retired model window");
        buffers.configure(profile);
        check(buffers.pro(fast,5)==null, "Reintroduced model starts a fresh stream");

        byte[] typed=ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[]{'G','A','I','B'}).putShort((short)1).putDouble(.8)
                .put(new byte[]{'R','V','M','2'}).putShort((short)1).put((byte)3).put((byte)0).array();
        check(ModelWire.decode(typed,List.of(fast))[0].model().equals(fast), "Dynamic wire reply routes to its requested model");
        reject(() -> ModelWire.decode(typed,List.of(deep)), "Cross-model wire replies rejected");
        var cursor=new StreamWindowCursor(java.util.UUID.randomUUID(),fast,60,5);
        // Constructor enforces the chosen collector's minimum rather than a hard-coded model ID.
        reject(() -> new StreamWindowCursor(java.util.UUID.randomUUID(),deep,50,5), "Dynamic Ultra model cannot use a Pro-sized window");

        var yaml=new YamlConfiguration();yaml.set("analyze.models.aim_fast.buffer.source","local");
        yaml.set("analyze.models.aim_fast.buffer.flag",80);
        check(ModelBufferConfig.resolve(yaml,fast,null).flag()==80, "Per-model local settings accept arbitrary profile IDs");
        JsonObject policy=JsonParser.parseString("""
            {"roles":{"alert":true,"mitigate":false,"punish":true},"mitigation_weight":1,
             "buffer":{"flag":50,"reset":25,"multiplier":100,"decrease":0.25,"weight":1,
             "primary_multiplier":2,"threshold":0.85,"legit_threshold":0.1,"inherit":"combat_deep"}}
            """).getAsJsonObject();
        check(ModelPolicy.parse(policy,fast).buffer().inherit().equals(deep), "Dynamic model buffer owner resolves by ID");
        var cloud=JsonParser.parseString("""
            {"groups":[{"name":"custom","models":["aim_fast","combat_deep"],"checks":["AimAI"],
            "remove_violations_after":300,"commands":["1:1 [log]"],"stages":{}}]}
            """).getAsJsonObject();
        check(CloudPunishments.parsePolicy(cloud).groups().get(0).models().contains(fast), "Punishment rules target custom models");

        for(String field:List.of("wire_id","capture","id")){
            var bad=input.deepCopy();var m=bad.getAsJsonArray("models").get(2).getAsJsonObject();
            if(field.equals("wire_id"))m.addProperty(field,2);
            else m.addProperty(field,field.equals("capture")?"unsupported":"../bad");
            reject(() -> PluginModelProfile.parse(bad), "Malformed or conflicting definition rejected: "+field);
        }
        var missing=input.deepCopy();missing.getAsJsonArray("models").get(2).getAsJsonObject().remove("capture");
        reject(() -> PluginModelProfile.parse(missing), "Custom models require an explicit supported capture format");
        check(PluginModelProfile.parse(input).models().size()==4, "Rejected profiles leave no registry side effects");
        try (var stream = DynamicModelContractTest.class.getResourceAsStream("/api-dynamic-profile.json")) {
            check(stream != null, "Rust API fixture exists");
            var api = PluginModelProfile.parse(JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
            check(api.models().size() == 3 && api.primary().key().equals("aim_fast"), "Actual Rust API profile is accepted by the Java plugin");
            check(api.primary().wireId() == 3 && api.primary().proCapture(), "Rust and Java agree on dynamic wire ID and capture schema");
            check(api.models().get(api.primary()).policy().buffer().inherit().equals(ModelId.ULTRA), "Inherited buffer policy survives the Rust to Java boundary");
        }
        System.out.println("Four-model API profile, independent captures, dynamic wire IDs, local policies and punishment routing passed");
    }
    private static void reject(Runnable call,String message){boolean rejected=false;try{call.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,message);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
