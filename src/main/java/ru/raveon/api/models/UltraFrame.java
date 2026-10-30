package ru.raveon.api.models;

import java.util.Arrays;
import java.util.Locale;

public final class UltraFrame {
    public static final int DATASET_FEATURE_COUNT = 24 + TelemetryField.COUNT;
    public static final int INFERENCE_FEATURE_COUNT = DATASET_FEATURE_COUNT;
    public static final String BASE_HEADER =
            "delta_yaw,delta_pitch,accel_yaw,accel_pitch,jerk_yaw,jerk_pitch,gcd_error_yaw,gcd_error_pitch";
    public static final int ROTATION_PRESENT = 0;
    public static final int PITCH = 1;
    public static final int MODE_YAW = 2;
    public static final int MODE_PITCH = 3;
    public static final int MODE_YAW_VALID = 4;
    public static final int MODE_PITCH_VALID = 5;
    public static final int DELTA_DOTS_YAW = 6;
    public static final int DELTA_DOTS_PITCH = 7;
    public static final int TARGET_VALID = 8;
    public static final int TARGET_AIM_ANGLE = 9;
    public static final int TARGET_REL_X = 10;
    public static final int TARGET_REL_Y = 11;
    public static final int TARGET_REL_Z = 12;
    public static final int TARGETS_IN_RANGE_COUNT = 13;
    public static final int HELD_ITEM_CLASS = 14;
    public static final int PLAYER_PING = 15;
    public static final String CONTEXT_HEADER =
            "rotation_present,pitch,mode_yaw,mode_pitch,mode_yaw_valid,mode_pitch_valid,delta_dots_yaw,delta_dots_pitch,target_valid,target_aim_angle,target_rel_x,target_rel_y,target_rel_z,targets_in_range_count,held_item_class,player_ping";
    public static final int CONTEXT_FEATURE_COUNT = CONTEXT_HEADER.split(",").length;
    private final float[] features;
    private volatile float[] inferenceFeatures;
    private final float[] telemetry;

    public UltraFrame(
            float deltaYaw,
            float deltaPitch,
            float accelYaw,
            float accelPitch,
            float jerkYaw,
            float jerkPitch,
            float gcdErrorYaw,
            float gcdErrorPitch,
            float[] contextFeatures) {
        if (contextFeatures != null && contextFeatures.length != CONTEXT_FEATURE_COUNT) {
            throw new IllegalArgumentException("Expected 16 context features");
        } else {
            this.telemetry = TelemetryField.unavailable();
            this.features = new float[24];
            this.features[0] = deltaYaw;
            this.features[1] = deltaPitch;
            this.features[2] = accelYaw;
            this.features[3] = accelPitch;
            this.features[4] = jerkYaw;
            this.features[5] = jerkPitch;
            this.features[6] = gcdErrorYaw;
            this.features[7] = gcdErrorPitch;
            if (contextFeatures != null) {
                System.arraycopy(contextFeatures, 0, this.features, 8, CONTEXT_FEATURE_COUNT);
            }
        }
    }

    public UltraFrame withTelemetry(float[] values) {
        return new UltraFrame(features, values);
    }

    private UltraFrame(float[] features, float[] telemetry) {
        this.features = features.clone();
        this.telemetry = TelemetryField.validate(telemetry);
    }

    public static UltraFrame fromFeatures(float[] values) {
        if (values.length == DATASET_FEATURE_COUNT)
            return new UltraFrame(Arrays.copyOf(values, 24), Arrays.copyOfRange(values, 24, values.length));
        if (values.length != 24) {
            throw new IllegalArgumentException("Expected 24 base features");
        } else {
            return new UltraFrame(
                    values[0],
                    values[1],
                    values[2],
                    values[3],
                    values[4],
                    values[5],
                    values[6],
                    values[7],
                    Arrays.copyOfRange(values, 8, values.length));
        }
    }

    public float[] datasetFeatures() {
        float[] row = Arrays.copyOf(features, DATASET_FEATURE_COUNT);
        System.arraycopy(telemetry, 0, row, 24, telemetry.length);
        return row;
    }

    public float[] inferenceFeatures() {
        float[] inputs = this.inferenceFeatures;
        if (inputs == null) {
            inputs = datasetFeatures();

            for (int index = 0; index < 24; index++) {
                inputs[index] = Float.parseFloat(formatDatasetValue(this.features[index]));
            }

            this.inferenceFeatures = inputs;
        }

        return (float[]) inputs.clone();
    }

    public static String csvHeader() {
        return "is_cheating," + BASE_HEADER + "," + CONTEXT_HEADER + "," + String.join(",", TelemetryField.NAMES);
    }

    public String toCsvRow(boolean cheater) {
        StringBuilder row = new StringBuilder(cheater ? "1" : "0");

        for (float value : this.features) {
            row.append(',').append(formatDatasetValue(value));
        }

        for (float value : telemetry) row.append(',').append(Float.toString(value));
        return row.toString();
    }

    private static String formatDatasetValue(float value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
