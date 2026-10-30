package ru.raveon.api.models;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum TelemetryField {
    SEQUENCE_ID,
    TICK_INDEX,
    DT_MS,
    TARGET_ENTITY_ID,
    YAW,
    PITCH,
    X,
    Y,
    Z,
    ON_GROUND,
    SPRINTING,
    SNEAKING,
    ACTIVE_ITEM,
    FLYING,
    SWIMMING,
    GLIDING,
    IN_VEHICLE,
    IS_CLIMBING,
    IN_WATER,
    POSE,
    INPUT_FORWARD,
    INPUT_BACKWARD,
    INPUT_LEFT,
    INPUT_RIGHT,
    INPUT_JUMP,
    INPUT_SHIFT,
    INPUT_SPRINT,
    GAME_MODE,
    FOOD_LEVEL,
    GROUND_FRICTION,
    FALL_DISTANCE,
    MOVEMENT_SPEED,
    JUMP_AMPLIFIER,
    SLOW_FALLING,
    PLAYER_PING,
    SPEED_AMPLIFIER,
    SLOWNESS_AMPLIFIER,
    HASTE_AMPLIFIER,
    MINING_FATIGUE_AMPLIFIER,
    ENTITY_INTERACTION_RANGE,
    SCALE,
    GRAVITY,
    JUMP_STRENGTH,
    STEP_HEIGHT,
    TARGET_VALID,
    TARGET_DISTANCE,
    TARGET_AIM_ANGLE,
    TARGET_REL_X,
    TARGET_REL_Y,
    TARGET_REL_Z,
    TICKS_TO_ATTACK,
    ATTACK_THIS_TICK,
    AIM_ERROR,
    SAME_TARGET_AS_PREV_ATTACK,
    TARGETS_IN_RANGE_COUNT,
    IS_CRITICAL,
    RAYTRACE_HIT,
    ATTACK_RAY_OCCLUDED,
    RAYTRACE_MISS_DISTANCE,
    INPUT_VALID,
    HIT_POINT_REL_X,
    HIT_POINT_REL_Y,
    HIT_POINT_REL_Z,
    TICKS_SINCE_ATTACK,
    SWUNG_THIS_TICK,
    ATTACK_COOLDOWN_PROGRESS,
    ATTACK_SPEED,
    IS_SPRINTING_ON_ATTACK,
    RECEIVED_VELOCITY_THIS_TICK,
    VELOCITY_X,
    VELOCITY_Y,
    VELOCITY_Z,
    TICKS_SINCE_VELOCITY,
    RECEIVED_EXPLOSION_THIS_TICK,
    TICKS_SINCE_EXPLOSION,
    TICKS_SINCE_USE_ITEM,
    ROTATION_PRESENT,
    MODE_YAW,
    MODE_PITCH,
    MODE_YAW_VALID,
    MODE_PITCH_VALID,
    DELTA_DOTS_YAW,
    DELTA_DOTS_PITCH,
    ACTIVE_FIREWORK_COUNT,
    STUCK_MULT_X,
    STUCK_MULT_Y,
    STUCK_MULT_Z,
    ON_SLIME,
    ON_HONEY,
    ON_SOUL_SAND,
    ON_MUD,
    LEVITATION_AMPLIFIER,
    SWIM_FRICTION,
    RIPTIDE_ACTIVE,
    TELEPORT_TICK,
    EXPOSURE_FRACTION,
    BOX_INFLATION,
    HEALTH,
    DAMAGE_TAKEN_THIS_TICK,
    TICKS_SINCE_DAMAGE,
    WINDOW_START,
    TARGET_TYPE,
    CRYSTAL_SPAWN_TO_ATTACK,
    USE_OFFHAND,
    NEARBY_CRYSTAL_COUNT,
    ANCHOR_CHARGE,
    ANCHOR_USE_INTERVAL,
    PLACE_FACE,
    PLACE_BLOCK_CLASS,
    PLACE_BLOCK_X,
    PLACE_BLOCK_Y,
    PLACE_BLOCK_Z,
    PLACE_CURSOR_X,
    PLACE_CURSOR_Y,
    PLACE_CURSOR_Z,
    PLACE_INSIDE_BLOCK,
    PLACES_THIS_TICK,
    HELD_ITEM_CLASS,
    OFFHAND_ITEM_CLASS,
    SLOT_SWITCHES_THIS_TICK,
    EXPLOSION_KB_X,
    EXPLOSION_KB_Y,
    EXPLOSION_KB_Z,
    HITTABLE_ENTITIES_COUNT,
    AIM_ERROR_YAW,
    AIM_ERROR_PITCH,
    RAYTRACE_DIRS_HIT_COUNT,
    RAYTRACE_HIT_STRICT;

    public static final int COUNT = values().length;
    public static final float UNAVAILABLE = -Float.MAX_VALUE;
    public static final List<String> NAMES = Arrays.stream(values())
            .map(field -> field.name().toLowerCase(Locale.ROOT))
            .map(name -> ("," + UltraFrame.CONTEXT_HEADER + ",").contains("," + name + ",")
                    ? "telemetry_" + name : name)
            .toList();

    public static float[] unavailable() {
        float[] values = new float[COUNT];
        Arrays.fill(values, UNAVAILABLE);
        return values;
    }

    public static float[] validate(float[] values) {
        if (values.length != COUNT) throw new IllegalArgumentException("Telemetry width mismatch");
        float[] copy = values.clone();
        for (float value : copy)
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite telemetry");
        return copy;
    }
}
