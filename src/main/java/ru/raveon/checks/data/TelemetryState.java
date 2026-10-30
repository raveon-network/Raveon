package ru.raveon.checks.data;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import ru.raveon.Raveon;
import ru.raveon.api.models.TelemetryField;
import ru.raveon.utils.SchedulerUtils;
import ru.raveon.utils.entity.EntitySnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import static ru.raveon.api.models.TelemetryField.*;

public final class TelemetryState {
    private static final TelemetryField[] COUNTERS = {
            TICKS_SINCE_ATTACK, TICKS_SINCE_VELOCITY, TICKS_SINCE_EXPLOSION,
            TICKS_SINCE_USE_ITEM, TICKS_SINCE_DAMAGE};
    private static final TelemetryField[] TRANSIENTS = {
            ATTACK_THIS_TICK, SWUNG_THIS_TICK, RECEIVED_VELOCITY_THIS_TICK,
            VELOCITY_X, VELOCITY_Y, VELOCITY_Z, RECEIVED_EXPLOSION_THIS_TICK,
            EXPLOSION_KB_X, EXPLOSION_KB_Y, EXPLOSION_KB_Z, TELEPORT_TICK,
            DAMAGE_TAKEN_THIS_TICK, PLACES_THIS_TICK, SLOT_SWITCHES_THIS_TICK};
    private static final class AttributeCache {
        private static final Map<String, Attribute> VALUES = attributes();
    }
    private static final java.lang.reflect.Method CLIMBING = climbingMethod();
    private final float[] values = TelemetryField.unavailable();
    private float[] frame = TelemetryField.unavailable();
    private UUID target;
    private int targetId = -1;
    private int previousTargetId = -1;
    private long tick;
    private long lastSampleNanos;
    private long lastGeometryNanos;
    private long sampleNanos;
    private long lastAnchorTick = -1;
    private int sequence;
    private int slot = -1;
    private boolean hasPosition;
    private double x;
    private double y;
    private double z;
    private float previousYaw;
    private float previousPitch;

    public TelemetryState() {
        reset();
    }

    public void reset() {
        Arrays.fill(values, UNAVAILABLE);
        for (TelemetryField field : COUNTERS) put(field, 255);
        for (TelemetryField field : TRANSIENTS) put(field, 0);
        put(INPUT_VALID, 0);
        put(ACTIVE_ITEM, 0);
        put(USE_OFFHAND, 0);
        put(SAME_TARGET_AS_PREV_ATTACK, 0);
        put(IS_SPRINTING_ON_ATTACK, 0);
        put(PLACE_INSIDE_BLOCK, 0);
        put(PLACE_BLOCK_X, 0); put(PLACE_BLOCK_Y, 0); put(PLACE_BLOCK_Z, 0);
        previousYaw = previousPitch = 0;
        put(PLACE_FACE, -1);
        put(PLACE_BLOCK_CLASS, 0);
        put(PLACE_CURSOR_X, -1);
        put(PLACE_CURSOR_Y, -1);
        put(PLACE_CURSOR_Z, -1);
        put(ANCHOR_CHARGE, -1);
        put(ANCHOR_USE_INTERVAL, -1);
        put(CRYSTAL_SPAWN_TO_ATTACK, -1);
        target = null;
        targetId = previousTargetId = -1;
        tick = 0;
        lastSampleNanos = 0;
        sampleNanos = 0;
        lastAnchorTick = -1;
        hasPosition = false;
        slot = -1;
        sequence++;
        frame = TelemetryField.unavailable();
    }

    public void apply(Player player, TelemetryUpdate update) {
        double[] a = update.values();
        switch (update.kind()) {
            case MOVEMENT -> {
                tick++;
                if (a[0] != 0) {
                    x = a[1]; y = a[2]; z = a[3];
                    hasPosition = true;
                }
                put(ON_GROUND, a[4]);
                sampleNanos = (long) a[5];
                for (TelemetryField field : COUNTERS) put(field, Math.min(255, get(field) + 1));
            }
            case INPUT -> {
                TelemetryField[] fields = {INPUT_FORWARD, INPUT_BACKWARD, INPUT_LEFT,
                        INPUT_RIGHT, INPUT_JUMP, INPUT_SHIFT, INPUT_SPRINT};
                for (int i = 0; i < fields.length; i++) put(fields[i], a[i]);
                put(INPUT_VALID, 1);
            }
            case SWING -> put(SWUNG_THIS_TICK, 1);
            case SLOT -> {
                if (slot != (int) a[0]) put(SLOT_SWITCHES_THIS_TICK, Math.min(32767, get(SLOT_SWITCHES_THIS_TICK) + 1));
                slot = (int) a[0];
                put(ACTIVE_ITEM, 0);
            }
            case USE -> {
                boolean usedOffhand = a[0] != 0;
                put(USE_OFFHAND, a[0]);
                put(TICKS_SINCE_USE_ITEM, 0);
                put(ACTIVE_ITEM, activeItem(usedOffhand ? player.getInventory().getItemInOffHand()
                        : player.getInventory().getItemInMainHand()));
            }
            case RELEASE -> put(ACTIVE_ITEM, 0);
            case PLACE -> {
                put(PLACE_BLOCK_X, a[0]); put(PLACE_BLOCK_Y, a[1]); put(PLACE_BLOCK_Z, a[2]);
                put(PLACE_FACE, a[3]); put(PLACE_CURSOR_X, a[4]); put(PLACE_CURSOR_Y, a[5]);
                put(PLACE_CURSOR_Z, a[6]); put(PLACE_INSIDE_BLOCK, a[7]);
                put(USE_OFFHAND, a[8]); put(TICKS_SINCE_USE_ITEM, 0);
                put(PLACES_THIS_TICK, Math.min(32767, get(PLACES_THIS_TICK) + 1));
                Block block = safeBlock(player, (int) a[0], (int) a[1], (int) a[2]);
                put(PLACE_BLOCK_CLASS, block == null ? 5 : blockClass(block.getType()));
                if (block != null && block.getBlockData() instanceof RespawnAnchor anchor) {
                    put(ANCHOR_CHARGE, anchor.getCharges());
                    put(ANCHOR_USE_INTERVAL, lastAnchorTick < 0 ? -1 : Math.min(32767, tick - lastAnchorTick));
                    lastAnchorTick = tick;
                }
            }
            case VELOCITY -> {
                put(RECEIVED_VELOCITY_THIS_TICK, 1); put(TICKS_SINCE_VELOCITY, 0);
                put(VELOCITY_X, a[0]); put(VELOCITY_Y, a[1]); put(VELOCITY_Z, a[2]);
            }
            case EXPLOSION -> {
                put(RECEIVED_EXPLOSION_THIS_TICK, 1); put(TICKS_SINCE_EXPLOSION, 0);
                put(EXPLOSION_KB_X, a[0]); put(EXPLOSION_KB_Y, a[1]); put(EXPLOSION_KB_Z, a[2]);
            }
            case TELEPORT -> {
                reset();
                put(TELEPORT_TICK, 1);
            }
            case DAMAGE -> {
                put(DAMAGE_TAKEN_THIS_TICK, get(DAMAGE_TAKEN_THIS_TICK) + Math.max(0, a[0]));
                put(TICKS_SINCE_DAMAGE, 0);
            }
        }
    }

    public void attack(Player player, int entityId, UUID uuid) {
        put(SAME_TARGET_AS_PREV_ATTACK, entityId == previousTargetId);
        previousTargetId = entityId;
        targetId = entityId;
        target = uuid;
        put(ATTACK_THIS_TICK, 1);
        put(IS_SPRINTING_ON_ATTACK, player.isSprinting());
        put(ATTACK_COOLDOWN_PROGRESS, Math.min(1,
                (get(TICKS_SINCE_ATTACK) + 0.5) * attribute(player, "attack_speed", 4) / 20));
        put(TICKS_SINCE_ATTACK, 0);
    }

    public float[] frame() {
        return frame.clone();
    }

    public void capture(Player player, float yaw, float pitch, float[] aimFrame) {
        Location location = player.getLocation();
        if (!hasPosition) {
            x = location.getX(); y = location.getY(); z = location.getZ();
        }
        put(SEQUENCE_ID, sequence); put(TICK_INDEX, tick);
        put(DT_MS, lastSampleNanos == 0 ? 0 : Math.min(10000, Math.max(0, (sampleNanos - lastSampleNanos) / 1_000_000.0)));
        lastSampleNanos = sampleNanos;
        put(YAW, yaw); put(PITCH, pitch);
        put(X, x); put(Y, y); put(Z, z);
        put(ROTATION_PRESENT, 1);
        put(SPRINTING, player.isSprinting()); put(SNEAKING, player.isSneaking());
        put(FLYING, player.isFlying()); put(SWIMMING, player.isSwimming());
        put(GLIDING, player.isGliding()); put(IN_VEHICLE, player.isInsideVehicle());
        put(IS_CLIMBING, climbing(player)); put(IN_WATER, player.isInWater());
        put(POSE, pose(player.getPose().name())); put(GAME_MODE, player.getGameMode().getValue());
        put(FOOD_LEVEL, player.getFoodLevel()); put(HEALTH, player.getHealth());
        put(FALL_DISTANCE, player.getFallDistance());
        put(MOVEMENT_SPEED, attribute(player, "movement_speed", 0.1));
        put(ENTITY_INTERACTION_RANGE, attribute(player, "entity_interaction_range", player.getGameMode().name().equals("CREATIVE") ? 5 : 3));
        put(SCALE, attribute(player, "scale", 1)); put(GRAVITY, attribute(player, "gravity", 0.08));
        put(JUMP_STRENGTH, attribute(player, "jump_strength", 0.42));
        put(STEP_HEIGHT, attribute(player, "step_height", 0.6));
        put(ATTACK_SPEED, attribute(player, "attack_speed", 4));
        if (get(ATTACK_THIS_TICK) == 0)
            put(ATTACK_COOLDOWN_PROGRESS, Math.min(1, (get(TICKS_SINCE_ATTACK) + 0.5) * get(ATTACK_SPEED) / 20));
        put(RIPTIDE_ACTIVE, player.isRiptiding());
        put(ACTIVE_ITEM, player.isHandRaised() ? activeItem(player.getActiveItem()) : 0);
        put(USE_OFFHAND, get(PLACES_THIS_TICK) != 0 ? get(USE_OFFHAND)
                : player.isHandRaised() && player.getHandRaised() == org.bukkit.inventory.EquipmentSlot.OFF_HAND ? 1 : 0);
        put(HELD_ITEM_CLASS, UltraState.classifyItem(player.getInventory().getItemInMainHand()));
        put(OFFHAND_ITEM_CLASS, UltraState.classifyItem(player.getInventory().getItemInOffHand()));
        effects(player);
        blocks(player, location);
        put(PLAYER_PING, aimFrame[23]);
        TelemetryField[] aimFields = {MODE_YAW, MODE_PITCH, MODE_YAW_VALID, MODE_PITCH_VALID, DELTA_DOTS_YAW, DELTA_DOTS_PITCH};
        for (int i = 0; i < aimFields.length; i++) put(aimFields[i], aimFrame[10 + i]);
        var index = Raveon.INSTANCE.getTargetEntityIndex();
        EntitySnapshot snapshot = target == null ? null : index.getSnapshot(target);
        if (snapshot != null && (!snapshot.valid() || !snapshot.worldId().equals(player.getWorld().getUID()))) snapshot = null;
        List<EntitySnapshot> nearby = index.nearbySnapshots(player, 8);
        if (snapshot == null && nearby != null)
            for (EntitySnapshot entity : nearby)
                if (entity.entityId() == targetId) { snapshot = entity; break; }
        nearby(player, nearby);
        put(TARGET_ENTITY_ID, targetId);
        put(TARGET_VALID, snapshot != null);
        put(TICKS_TO_ATTACK, get(ATTACK_THIS_TICK) != 0 ? 0 : UNAVAILABLE);
        put(TARGET_TYPE, snapshot == null ? 5 : targetType(snapshot));
        put(WINDOW_START, get(ATTACK_THIS_TICK) != 0 ? windowType(snapshot) :
                get(RECEIVED_EXPLOSION_THIS_TICK) != 0 ? 6 :
                get(PLACES_THIS_TICK) != 0 && get(PLACE_BLOCK_CLASS) == 3 ? 4 : 0);
        put(CRYSTAL_SPAWN_TO_ATTACK, snapshot != null && targetType(snapshot) == 2 && get(ATTACK_THIS_TICK) != 0
                ? Math.min(32767, snapshot.ageTicks()) : -1);
        if (lastGeometryNanos == 0 || sampleNanos - lastGeometryNanos >= 25_000_000L) {
            TelemetryGeometry.capture(values, player, snapshot, x, y, z, yaw, pitch, previousYaw, previousPitch);
            lastGeometryNanos = sampleNanos;
        } else {
            TelemetryGeometry.unavailable(values);
        }
        previousYaw = yaw;
        previousPitch = pitch;
        frame = values.clone();
        finishFrame();
    }

    public void finishFrame() {
        for (TelemetryField field : TRANSIENTS) put(field, 0);
        put(PLACE_FACE, -1); put(PLACE_BLOCK_CLASS, 0); put(PLACE_INSIDE_BLOCK, 0);
        put(PLACE_CURSOR_X, -1); put(PLACE_CURSOR_Y, -1); put(PLACE_CURSOR_Z, -1);
        put(ANCHOR_CHARGE, -1); put(ANCHOR_USE_INTERVAL, -1);
    }

    private void nearby(Player player, List<EntitySnapshot> nearby) {
        if (nearby == null) {
            put(TARGETS_IN_RANGE_COUNT, UNAVAILABLE); put(HITTABLE_ENTITIES_COUNT, UNAVAILABLE);
            put(NEARBY_CRYSTAL_COUNT, UNAVAILABLE); put(ACTIVE_FIREWORK_COUNT, UNAVAILABLE);
            return;
        }
        int living = 0, hittable = 0, crystals = 0, fireworks = 0;
        for (EntitySnapshot entity : nearby) {
            if (entity.type().equals("ENDER_CRYSTAL") || entity.type().equals("END_CRYSTAL")) crystals++;
            if (player.getUniqueId().equals(entity.attachedTo())) fireworks++;
            if (entity.distanceSquared(x, y, z) <= 36 && !entity.spectator()) {
                if (entity.living()) living++;
                if (entity.living() || targetType(entity) == 2 || targetType(entity) == 3) hittable++;
            }
        }
        put(TARGETS_IN_RANGE_COUNT, Math.min(255, living)); put(HITTABLE_ENTITIES_COUNT, Math.min(255, hittable));
        put(NEARBY_CRYSTAL_COUNT, Math.min(255, crystals)); put(ACTIVE_FIREWORK_COUNT, EntitySnapshot.supportsFireworkAttachment() ? Math.min(255, fireworks) : UNAVAILABLE);
    }

    private void effects(Player player) {
        TelemetryField[] types = {JUMP_AMPLIFIER, SPEED_AMPLIFIER, SLOWNESS_AMPLIFIER,
                HASTE_AMPLIFIER, MINING_FATIGUE_AMPLIFIER, LEVITATION_AMPLIFIER};
        for (TelemetryField field : types) put(field, -1);
        put(SLOW_FALLING, 0);
        boolean dolphins = false;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            TelemetryField field = switch (effect.getType().getKey().getKey()) {
                case "jump_boost" -> JUMP_AMPLIFIER;
                case "speed" -> SPEED_AMPLIFIER;
                case "slowness" -> SLOWNESS_AMPLIFIER;
                case "haste" -> HASTE_AMPLIFIER;
                case "mining_fatigue" -> MINING_FATIGUE_AMPLIFIER;
                case "levitation" -> LEVITATION_AMPLIFIER;
                default -> null;
            };
            if (field != null) put(field, effect.getAmplifier());
            if (effect.getType().getKey().getKey().equals("slow_falling")) put(SLOW_FALLING, 1);
            if (effect.getType().getKey().getKey().equals("dolphins_grace")) dolphins = true;
        }
        double efficiency = attribute(player, "water_movement_efficiency", -1);
        if (efficiency < 0) {
            ItemStack boots = player.getInventory().getBoots();
            efficiency = 0;
            if (boots != null)
                for (var enchant : boots.getEnchantments().entrySet())
                    if (enchant.getKey().getKey().getKey().equals("depth_strider"))
                        efficiency = Math.min(3, enchant.getValue()) / 3.0;
        }
        if (get(ON_GROUND) == 0) efficiency *= 0.5;
        double friction = player.isSprinting() ? 0.9 : 0.8;
        put(SWIM_FRICTION, dolphins ? 0.96 : friction + (0.546 - friction) * Math.min(1, efficiency));
    }

    private void blocks(Player player, Location location) {
        int bx = location.getBlockX(), by = location.getBlockY(), bz = location.getBlockZ();
        Block ground = safeBlock(player, bx, (int) Math.floor(location.getY() - 0.500001), bz);
        Block body = safeBlock(player, bx, by, bz);
        if (ground == null || body == null) {
            for (TelemetryField field : new TelemetryField[] {GROUND_FRICTION, STUCK_MULT_X, STUCK_MULT_Y, STUCK_MULT_Z,
                    ON_SLIME, ON_HONEY, ON_SOUL_SAND, ON_MUD}) put(field, UNAVAILABLE);
            return;
        }
        String floor = ground.getType().name(), inside = body.getType().name();
        put(GROUND_FRICTION, floor.equals("SLIME_BLOCK") ? 0.8 : floor.equals("BLUE_ICE") ? 0.989 :
                floor.contains("ICE") ? 0.98 : 0.6);
        put(ON_SLIME, floor.equals("SLIME_BLOCK")); put(ON_HONEY, floor.equals("HONEY_BLOCK"));
        put(ON_SOUL_SAND, floor.equals("SOUL_SAND")); put(ON_MUD, floor.equals("MUD"));
        double horizontal = 1, vertical = 1;
        if (inside.equals("COBWEB")) { horizontal = 0.25; vertical = 0.05; }
        else if (inside.equals("SWEET_BERRY_BUSH")) { horizontal = 0.8; vertical = 0.75; }
        else if (inside.equals("POWDER_SNOW")) { horizontal = 0.9; vertical = 1.5; }
        put(STUCK_MULT_X, horizontal); put(STUCK_MULT_Y, vertical); put(STUCK_MULT_Z, horizontal);
    }

    private static java.lang.reflect.Method climbingMethod() {
        try { return Player.class.getMethod("isClimbing"); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    private static boolean climbing(Player player) {
        if (CLIMBING != null) {
            try { return (Boolean) CLIMBING.invoke(player); }
            catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        }
        Location location = player.getLocation();
        Block block = safeBlock(player, location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (block == null) return false;
        String type = block.getType().name();
        return type.equals("LADDER") || type.equals("VINE") || type.contains("VINES")
                || type.equals("SCAFFOLDING");
    }

    private static int activeItem(ItemStack item) {
        if (item == null) return 5;
        return switch (item.getType().name()) {
            case "SHIELD" -> 1;
            case "BOW", "CROSSBOW" -> 3;
            case "TRIDENT" -> 4;
            case "POTION", "MILK_BUCKET", "HONEY_BOTTLE" -> 2;
            default -> item.getType().isEdible() ? 2 : 5;
        };
    }

    public static Block safeBlock(Player player, int x, int y, int z) {
        Location location = player.getLocation();
        if (Math.abs(x - location.getX()) > 9 || Math.abs(y - location.getY()) > 9
                || Math.abs(z - location.getZ()) > 9) return null;
        var world = player.getWorld();
        if (!world.isChunkLoaded(x >> 4, z >> 4) || !SchedulerUtils.isOwned(world, x >> 4, z >> 4)) return null;
        return world.getBlockAt(x, y, z);
    }

    private static int blockClass(Material material) {
        return switch (material.name()) {
            case "OBSIDIAN", "CRYING_OBSIDIAN" -> 1;
            case "BEDROCK" -> 2;
            case "RESPAWN_ANCHOR" -> 3;
            default -> material.isSolid() ? 4 : 0;
        };
    }

    private static int targetType(EntitySnapshot target) {
        if (target.type().equals("PLAYER")) return 0;
        if (target.living()) return 1;
        if (target.type().equals("ENDER_CRYSTAL") || target.type().equals("END_CRYSTAL")) return 2;
        if (target.type().contains("BOAT") || target.type().contains("MINECART")) return 3;
        return 4;
    }

    private static int windowType(EntitySnapshot target) {
        if (target == null) return 3;
        int type = targetType(target);
        return type <= 2 ? type : 3;
    }

    private static int pose(String pose) {
        return switch (pose) {
            case "STANDING" -> 0; case "FALL_FLYING" -> 1; case "SLEEPING" -> 2;
            case "SWIMMING" -> 3; case "SPIN_ATTACK" -> 4; case "SNEAKING", "CROUCHING" -> 5;
            case "LONG_JUMPING" -> 6; case "DYING" -> 7; default -> -1;
        };
    }

    private static Map<String, Attribute> attributes() {
        Map<String, Attribute> result = new HashMap<>();
        for (String key : List.of("movement_speed", "attack_speed", "entity_interaction_range",
                "scale", "gravity", "jump_strength", "step_height", "water_movement_efficiency")) {
            for (String prefix : List.of("", "GENERIC_", "PLAYER_")) {
                try {
                    result.put(key, (Attribute) Attribute.class.getField(prefix + key.toUpperCase(java.util.Locale.ROOT)).get(null));
                    break;
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        return Map.copyOf(result);
    }

    private static double attribute(Player player, String key, double fallback) {
        Attribute attribute = AttributeCache.VALUES.get(key);
        var value = attribute == null ? null : player.getAttribute(attribute);
        return value == null ? fallback : value.getValue();
    }

    private float get(TelemetryField field) { return values[field.ordinal()]; }
    private void put(TelemetryField field, boolean value) { put(field, value ? 1 : 0); }
    private void put(TelemetryField field, double value) {
        values[field.ordinal()] = Double.isFinite(value) && Math.abs(value) <= Float.MAX_VALUE ? (float) value : UNAVAILABLE;
    }
}
