package ru.raveon.verification;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import ru.raveon.Raveon;
import ru.raveon.api.models.*;
import ru.raveon.checks.data.*;
import ru.raveon.model.ModelId;
import ru.raveon.service.analyze.ModelWire;
import ru.raveon.utils.entity.TargetEntityIndex;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.function.BiFunction;
import static ru.raveon.api.models.TelemetryField.*;

public final class TelemetryVerification {
    private static int assertions;
    private static TargetEntityIndex index;

    public static final class Plugin extends Raveon {
        @Override public TargetEntityIndex getTargetEntityIndex() { return index; }
    }

    public static void main(String[] args) throws Exception {
        verifyWire();
        verifyInputs();
        verifyApi();
        if (args.length == 0 || !args[0].equals("modern")) verifyCapture();
        System.out.println("Telemetry verification passed: " + assertions
                + " assertions; CSV/API parity, immutable vectors, input validation, API linkage"
                + (args.length == 0 || !args[0].equals("modern") ? ", live collector fixtures" : ""));
    }

    private static void verifyWire() throws Exception {
        require(COUNT == 128 && NAMES.size() == 128 && new HashSet<>(NAMES).size() == 128, "schema width/names");
        require(NAMES.get(0).equals("sequence_id") && NAMES.get(127).equals("raytrace_hit_strict"), "schema order");
        Random random = new Random(7124);
        List<UltraFrame> frames = new ArrayList<>();
        for (int row = 0; row < 64; row++) {
            float[] base = new float[24], telemetry = new float[128];
            for (int i = 0; i < base.length; i++) base[i] = (random.nextFloat() - .5F) * 10000;
            for (int i = 0; i < telemetry.length; i++) telemetry[i] = random.nextFloat() * 100 - 50;
            telemetry[0] = row; telemetry[96] = UNAVAILABLE;
            UltraFrame frame = UltraFrame.fromFeatures(base).withTelemetry(telemetry);
            base[0] = 99999; telemetry[0] = 99999;
            require(frame.datasetFeatures()[24] == row, "constructor aliases inputs");
            float[] copied = frame.inferenceFeatures();
            copied[24] = 88888;
            require(frame.inferenceFeatures()[24] == row, "getter aliases storage");
            frames.add(frame);
        }
        byte[] request = ModelWire.ultra("fixture", frames);
        ByteBuffer buffer = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
        int table = buffer.getInt(0), vtable = table - buffer.getInt(table);
        int version = Short.toUnsignedInt(buffer.getShort(vtable + 12));
        require(buffer.getInt(table + version) == 3, "Ultra protocol version");
        int offset = Short.toUnsignedInt(buffer.getShort(vtable + 8));
        int vector = table + offset + buffer.getInt(table + offset);
        require(buffer.getInt(vector) == 64 * 152, "Ultra vector width");
        String[] header = UltraFrame.csvHeader().split(",");
        require(header.length == 153, "CSV header width");
        require(new HashSet<>(Arrays.asList(header)).size() == header.length, "duplicate CSV column names");
        require(NAMES.get(PITCH.ordinal()).equals("telemetry_pitch"), "duplicate context name must be qualified");
        var schemaNames = java.util.regex.Pattern.compile("\"name\":\\s*\"([^\"]+)\"")
                .matcher(java.nio.file.Files.readString(java.nio.file.Path.of("docs", "ultra-schema.json")));
        List<String> documented = new ArrayList<>();
        while (schemaNames.find()) documented.add(schemaNames.group(1));
        require(documented.equals(Arrays.asList(header).subList(1, header.length)), "documented schema order");
        int at = vector + 4;
        for (UltraFrame frame : frames) {
            String[] csv = frame.toCsvRow(false).split(",");
            require(csv.length == 153 && csv[0].equals("0"), "CSV shape");
            float[] expected = frame.inferenceFeatures();
            for (int i = 0; i < expected.length; i++) {
                float wire = buffer.getFloat(at); at += 4;
                require(Float.floatToIntBits(wire) == Float.floatToIntBits(Float.parseFloat(csv[i + 1])), "CSV/wire divergence at " + i);
                require(Float.floatToIntBits(wire) == Float.floatToIntBits(expected[i]), "wire order");
            }
            require(Arrays.equals(frame.datasetFeatures(), UltraFrame.fromFeatures(frame.datasetFeatures()).datasetFeatures()), "full-vector round trip");
        }
        float[] invalid = new float[128]; invalid[17] = Float.NaN;
        rejected(() -> frames.get(0).withTelemetry(invalid));
        rejected(() -> frames.get(0).withTelemetry(new float[127]));
        require(ModelId.PRO.features() == 8, "Pro width changed");
        byte[] pro = ModelWire.pro(List.of(new RotationFrame(1, 2, 3, 4, 5, 6, 7, 8)));
        buffer = ByteBuffer.wrap(pro).order(ByteOrder.LITTLE_ENDIAN);
        table = buffer.getInt(); vtable = table - buffer.getInt(table);
        version = Short.toUnsignedInt(buffer.getShort(vtable + 12));
        require(buffer.getInt(table + version) == 2, "Pro protocol changed");
        require(RotationFrame.csvHeader().split(",").length == 9, "Pro CSV changed");
    }

    private static void verifyInputs() {
        double[] raw = {1, 2, 3};
        TelemetryUpdate update = new TelemetryUpdate(TelemetryUpdate.Kind.VELOCITY, raw);
        raw[0] = 100;
        require(update.values()[0] == 1, "update aliases array");
        double[] read = update.values(); read[0] = 300;
        require(update.values()[0] == 1, "update exposes array");
        rejected(() -> new TelemetryUpdate(TelemetryUpdate.Kind.VELOCITY, new double[] {Double.NaN, 0, 0}));
        rejected(() -> new TelemetryUpdate(TelemetryUpdate.Kind.INPUT, new double[0]));
        require(Math.abs(TelemetryGeometry.distance(new BoundingBox(1, 1, 1, 2, 2, 2), new Vector(0, 0, 0)) - Math.sqrt(3)) < 1e-9, "point-box distance");
        require(TelemetryGeometry.look(0, 0).distance(new Vector(0, 0, 1)) < 1e-9, "look direction");
    }

    private static void verifyApi() throws Exception {
        for (String method : List.of("isInWater", "isRiptiding", "getPose", "getEyeHeight",
                "getActivePotionEffects", "getFallDistance", "isSwimming", "getHealth", "getFoodLevel"))
            require(Player.class.getMethod(method) != null, method);
        require(Block.class.getMethod("getBlockData") != null, "block data");
        boolean available;
        try { org.bukkit.entity.Firework.class.getMethod("getAttachedTo"); available = true; }
        catch (NoSuchMethodException ignored) { available = false; }
        require(ru.raveon.utils.entity.EntitySnapshot.supportsFireworkAttachment() == available, "firework API availability");
    }

    private static void verifyCapture() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        Raveon previousPlugin = Raveon.INSTANCE;
        serverField.set(null, proxy(Server.class, Map.of("isPrimaryThread", true)));
        index = new TargetEntityIndex();
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Raveon.INSTANCE = (Raveon) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null), Plugin.class);
        try {
            Map<String, Object> worldValues = new HashMap<>();
            worldValues.put("getUID", UUID.randomUUID());
            worldValues.put("isChunkLoaded", true);
            Chunk chunk = proxy(Chunk.class, Map.of("getEntities", new org.bukkit.entity.Entity[0]));
            worldValues.put("getChunkAt", chunk);
            worldValues.put("getBlockAt", proxy(Block.class, Map.of("getType", Material.AIR)));
            World world = proxy(World.class, worldValues);
            Map<String, Object> playerValues = new HashMap<>();
            playerValues.put("getWorld", world);
            playerValues.put("getLocation", new Location(world, 0, 64, 0));
            playerValues.put("getUniqueId", UUID.randomUUID());
            playerValues.put("getGameMode", GameMode.SURVIVAL);
            playerValues.put("getPose", Pose.STANDING);
            playerValues.put("getHealth", 20D);
            playerValues.put("getFoodLevel", 20);
            playerValues.put("getEyeHeight", 1.62D);
            playerValues.put("getActivePotionEffects", List.of());
            playerValues.put("getInventory", proxy(PlayerInventory.class, Map.of()));
            Player player = proxy(Player.class, playerValues);
            TelemetryState state = new TelemetryState();
            state.apply(player, new TelemetryUpdate(TelemetryUpdate.Kind.INPUT, new double[] {1, 0, 1, 0, 1, 0, 1}));
            state.apply(player, new TelemetryUpdate(TelemetryUpdate.Kind.VELOCITY, new double[] {.1, .2, .3}));
            state.apply(player, new TelemetryUpdate(TelemetryUpdate.Kind.EXPLOSION, new double[] {.4, .5, .6}));
            state.apply(player, new TelemetryUpdate(TelemetryUpdate.Kind.DAMAGE, new double[] {3}));
            state.attack(player, 42, null);
            state.apply(player, new TelemetryUpdate(TelemetryUpdate.Kind.MOVEMENT, new double[] {1, 1, 64, 2, 1, 1_000_000_000}));
            state.capture(player, 20, 10, new float[24]);
            float[] first = state.frame();
            require(first[X.ordinal()] == 1 && first[Z.ordinal()] == 2, "packet position lost");
            require(first[INPUT_FORWARD.ordinal()] == 1 && first[INPUT_VALID.ordinal()] == 1, "input lost");
            require(first[ATTACK_THIS_TICK.ordinal()] == 1 && first[TARGET_ENTITY_ID.ordinal()] == 42, "attack lost");
            require(first[VELOCITY_Y.ordinal()] == .2F && first[EXPLOSION_KB_Z.ordinal()] == .6F, "knockback lost");
            require(first[DAMAGE_TAKEN_THIS_TICK.ordinal()] == 3, "damage lost");
            require(first[BOX_INFLATION.ordinal()] == UNAVAILABLE, "invented compensated box");
            if (!ru.raveon.utils.entity.EntitySnapshot.supportsFireworkAttachment())
                require(first[ACTIVE_FIREWORK_COUNT.ordinal()] == UNAVAILABLE, "invented firework attachment");
            state.apply(player, new TelemetryUpdate(TelemetryUpdate.Kind.MOVEMENT, new double[] {0, 0, 0, 0, 1, 1_050_000_000}));
            state.capture(player, 21, 10, new float[24]);
            float[] second = state.frame();
            require(second[ATTACK_THIS_TICK.ordinal()] == 0 && second[VELOCITY_Y.ordinal()] == 0
                    && second[DAMAGE_TAKEN_THIS_TICK.ordinal()] == 0, "transient event leaked");
            require(second[DT_MS.ordinal()] == 50 && second[X.ordinal()] == 1, "time/retained position");
            state.reset();
            state.apply(player, new TelemetryUpdate(TelemetryUpdate.Kind.MOVEMENT, new double[] {0, 0, 0, 0, 0, 2_000_000_000}));
            state.capture(player, 0, 0, new float[24]);
            require(state.frame()[INPUT_VALID.ordinal()] == 0 && state.frame()[TARGET_ENTITY_ID.ordinal()] == -1, "reset leaked state");
            worldValues.put("isChunkLoaded", false);
            worldValues.put("getBlockAt", (BiFunction<Object, Object[], Object>) (object, args) -> { throw new AssertionError("unloaded block read"); });
            require(TelemetryState.safeBlock(player, 0, 64, 0) == null, "unloaded chunk accessed");
            require(TelemetryState.safeBlock(player, 10000, 64, 10000) == null, "remote block accessed");
        } finally {
            serverField.set(null, previousServer);
            Raveon.INSTANCE = previousPlugin;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
            if (method.getName().equals("equals")) return object == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            Object value = values.get(method.getName());
            if (value instanceof BiFunction<?, ?, ?> function) return ((BiFunction<Object, Object[], Object>) function).apply(object, args);
            if (values.containsKey(method.getName())) return value;
            Class<?> result = method.getReturnType();
            if (!result.isPrimitive()) return null;
            if (result == boolean.class) return false;
            if (result == long.class) return 0L;
            if (result == double.class) return 0D;
            if (result == float.class) return 0F;
            return result == void.class ? null : 0;
        });
    }

    private static void rejected(Runnable operation) {
        try { operation.run(); throw new AssertionError("invalid input accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
