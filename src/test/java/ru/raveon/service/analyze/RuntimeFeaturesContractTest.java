package ru.raveon.service.analyze;

import com.google.gson.*;
import ru.raveon.model.*;
import ru.raveon.manager.anticheat.*;
import ru.raveon.config.anticheat.ChecksConfigManager.*;
import ru.raveon.config.anticheat.PunishmentConfigManager;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

public final class RuntimeFeaturesContractTest {
    public static void run() throws Exception {
        testBuffers(); BufferInheritanceContractTest.run(); DynamicModelContractTest.run(); testRuleSync(); testFullWindows(); testCloudFile(); StreamIsolationContractTest.run();
        System.out.println("Runtime roles/buffers, session cache, rule conflicts, YAML and full-window wire checks passed");
    }

    private static void testBuffers() {
        AtomicLong clock = new AtomicLong(1);
        var cache = new SessionBufferCache(clock::get);
        UUID player = UUID.randomUUID();
        var state = cache.get(player);
        var settings = settings(.85, 50, 25, true, true);
        state.reload(settings);
        state.accept(ModelId.PRO, .95);
        double before = state.buffer(ModelId.PRO);
        check(before > 19.99 && before < 20.01, "API probability and primary weight");
        check(cache.get(player) == state, "Reconnect keeps UUID state");
        // A clock change and settings reload have no authority to delete detection buffers.
        clock.addAndGet(TimeUnit.DAYS.toNanos(1000));
        state.reload(settings(.85, 10, 5, true, true));
        check(state.buffer(ModelId.PRO) == before, "Policy changes retain previous buffer");
        check(!state.accept(ModelId.PRO, 0).crossed(), "Lowering threshold never punishes a legitimate answer retroactively");
        check(state.buffer(ModelId.PRO) < before, "Legitimate answers still decrease normally");
        check(state.accept(ModelId.PRO, .99).crossed() && state.buffer(ModelId.PRO) == 5, "Flag still resets normally");
        state.reload(settings(.85, 50, 25, false, false));
        check(state.accept(ModelId.PRO, .99) == null && state.buffer(ModelId.PRO) == 5, "Disabled models retain cached buffer");
        state.reload(settings(.85, 50, 25, true, false));
        var result = state.accept(ModelId.PRO, .99);
        check(!result.actions().punish() && !result.actions().alert(), "API role gates reach the action manager");
        cache.clear();
        var fresh = cache.get(player); fresh.reload(settings);
        check(fresh != state && fresh.buffer(ModelId.PRO) == 0, "Only plugin shutdown clears the cache");
    }

    private record Settings(Map<ModelId, AnalysisSettings> getModelSettings, BufferSettings getBufferSettings,
            ModelId getPrimaryModel, MitigationSettings getMitigationSettings, boolean isAnalysisEnabled) implements DetectionSettings {
        public boolean isModelEnabled(ModelId model) { return isAnalysisEnabled && getModelSettings.get(model).enabled(); }
        public int getAnalysisStep(ModelId model) { return getModelSettings.get(model).step(); }
        public int getAnalysisSequence(ModelId model) { return getModelSettings.get(model).window(); }
    }
    private static Settings settings(double threshold, double flag, double reset, boolean enabled, boolean roles) {
        var buffer = new ModelPolicy.Buffer(flag, reset, 100, .25, 1, 2, threshold, .1, null);
        var actions = new ModelActions(roles, roles, roles);
        return new Settings(Map.of(
                ModelId.PRO, new AnalysisSettings(enabled, 50, 10, 2500, threshold, actions, 1, ModelId.PRO, buffer),
                ModelId.ULTRA, new AnalysisSettings(enabled, 64, 32, 3200, threshold, actions, 1, ModelId.ULTRA, buffer)),
                new BufferSettings(50,25,100,.25,2,Map.of(ModelId.PRO,1.0,ModelId.ULTRA,1.0)), ModelId.PRO,
                new MitigationSettings(false,.5,30000,10000,3,3,10,20,.5), enabled);
    }

    private static CloudPunishments.Policy rules(String action) {
        JsonObject root = JsonParser.parseString("{\"groups\":[{\"name\":\"aim\",\"models\":[\"pro\"],\"checks\":[\"AimAI\"],\"remove_violations_after\":300,\"commands\":[\"1:1 " + action + "\"],\"stages\":{}}]}").getAsJsonObject();
        return CloudPunishments.parsePolicy(root);
    }
    private static void testRuleSync() {
        var local = rules("[log]"); var changed = rules("[alert]");
        check(RuleSyncPlan.decide(new CloudPunishments("sync",0,null),0,local,null).upload(), "Bootstrap local rules");
        check(RuleSyncPlan.decide(new CloudPunishments("sync",1,local),1,changed,local).upload(), "Local edit uploads with matching revision");
        var conflict = RuleSyncPlan.decide(new CloudPunishments("sync",2,local),1,changed,local);
        check(!conflict.upload() && conflict.conflict(), "Panel wins a simultaneous edit");
        check(!RuleSyncPlan.decide(new CloudPunishments("off",2,local),1,changed,local).upload(), "Off never uploads");
        check(RuleSyncPlan.decide(new CloudPunishments("upload",2,local),1,changed,local).upload(), "Upload source is local");
        check(CloudPunishments.parsePolicy(local.json()).equals(local), "Policy normalization is idempotent");
        check(local.groups().get(0).checks().equals(List.of("aimai")), "Case-insensitive check normalization");
        for (String invalid : List.of("0:0 ban p", "1:-1 ban p", "1:1")) {
            boolean rejected=false; try { CloudPunishments.validateCommand(invalid); } catch (RuntimeException e) { rejected=true; }
            check(rejected,"Invalid punishment command");
        }
    }

    private static byte[] fullWindow(ModelId model, int start, int count) {
        if (model == ModelId.PRO) {
            var frames = new ArrayList<ru.raveon.api.models.RotationFrame>();
            for (int i = start; i < start + count; i++) {
                float b = i * 8;
                frames.add(new ru.raveon.api.models.RotationFrame(b,b+1,b+2,b+3,b+4,b+5,b+6,b+7));
            }
            return ModelWire.pro(frames);
        }
        var frames = new ArrayList<ru.raveon.api.models.UltraFrame>();
        for (int i = start; i < start + count; i++) {
            float b = i * model.features();
            float[] context = new float[16], telemetry = new float[model.features()-24];
            for (int j=0;j<context.length;j++) context[j]=b+8+j;
            for (int j=0;j<telemetry.length;j++) telemetry[j]=b+24+j;
            frames.add(new ru.raveon.api.models.UltraFrame(b,b+1,b+2,b+3,b+4,b+5,b+6,b+7,context).withTelemetry(telemetry));
        }
        return ModelWire.ultra("11111111-1111-4111-8111-111111111111", frames);
    }

    private static void testFullWindows() throws Exception {
        // Consecutive overlapping captures must both remain complete on the wire.
        byte[] firstPro = fullWindow(ModelId.PRO, 0, 80), nextPro = fullWindow(ModelId.PRO, 20, 80);
        byte[] firstUltra = fullWindow(ModelId.ULTRA, 0, 96), nextUltra = fullWindow(ModelId.ULTRA, 16, 96);
        for (byte[] payload : List.of(firstPro,nextPro,firstUltra,nextUltra))
            check(payload[4]=='G' && payload[5]=='A' && payload[6]=='I' && payload[7]=='Q', "Full FlatBuffers request identifier");
        check(firstPro.length==nextPro.length && firstPro.length>80*8*4, "Pro sends all 80 frames every time");
        check(firstUltra.length==nextUltra.length && firstUltra.length>96*ModelId.ULTRA.features()*4, "Ultra sends all 96 frames every time");
        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/full-window-first.bin"),ModelWire.batch(List.of(firstPro,firstUltra)));
        Files.write(Path.of("build/full-window-next.bin"),ModelWire.batch(List.of(nextPro,nextUltra)));
    }

    private static void testCloudFile() throws Exception {
        Path root=Files.createTempDirectory("raveon-rule-test-");
        var plugin=(org.bukkit.plugin.Plugin)java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),new Class[]{org.bukkit.plugin.Plugin.class},
                (proxy,method,args) -> method.getName().equals("getDataFolder") ? root.toFile() : null);
        var policy=rules("[log]");
        PunishmentConfigManager.writeCloudPolicy(plugin,policy);
        var yaml=new org.bukkit.configuration.file.YamlConfiguration();
        yaml.load(root.resolve("features/punishments.yml").toFile());
        check(yaml.getStringList("aim.commands").equals(List.of("1:1 [log]")),"Cloud YAML keeps exact commands");
        check(yaml.getInt("aim.remove-violations-after")==300,"Cloud YAML preserves counter lifetime");
        Files.delete(root.resolve("features/punishments.yml")); Files.delete(root.resolve("features")); Files.delete(root);
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
