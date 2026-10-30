package ru.raveon.checks.data;

import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import ru.raveon.api.models.TelemetryField;
import ru.raveon.utils.entity.EntitySnapshot;
import java.util.List;
import static ru.raveon.api.models.TelemetryField.*;

public final class TelemetryGeometry {
    private static final TelemetryField[] TARGET_FIELDS = {TARGET_DISTANCE, TARGET_AIM_ANGLE,
            TARGET_REL_X, TARGET_REL_Y, TARGET_REL_Z, AIM_ERROR, IS_CRITICAL, RAYTRACE_HIT,
            ATTACK_RAY_OCCLUDED, RAYTRACE_MISS_DISTANCE, HIT_POINT_REL_X, HIT_POINT_REL_Y,
            HIT_POINT_REL_Z, EXPOSURE_FRACTION, AIM_ERROR_YAW, AIM_ERROR_PITCH,
            RAYTRACE_DIRS_HIT_COUNT, RAYTRACE_HIT_STRICT};
    private TelemetryGeometry() {}

    public static void unavailable(float[] values) {
        for (TelemetryField field : TARGET_FIELDS) values[field.ordinal()] = UNAVAILABLE;
        values[BOX_INFLATION.ordinal()] = UNAVAILABLE;
    }

    public static void capture(float[] row, Player player, EntitySnapshot target,
            double x, double y, double z, float yaw, float pitch, float lastYaw, float lastPitch) {
        for (TelemetryField field : TARGET_FIELDS) row[field.ordinal()] = 0;
        row[BOX_INFLATION.ordinal()] = UNAVAILABLE;
        if (target == null) return;
        BoundingBox box = target.boundingBox();
        row[TARGET_REL_X.ordinal()] = (float) (target.x() - x);
        row[TARGET_REL_Y.ordinal()] = (float) (target.y() - y);
        row[TARGET_REL_Z.ordinal()] = (float) (target.z() - z);
        List<Vector> directions = List.of(look(yaw, pitch), look(lastYaw, pitch), look(lastYaw, lastPitch));
        double[] eyes = {1.62, 1.27, 0.4};
        double angle = 180, centerAngle = 180, centerDistance = 0, distance = Double.MAX_VALUE;
        double miss = Double.MAX_VALUE;
        int hits = 0;
        Vector hit = null;
        double range = Math.max(6, row[ENTITY_INTERACTION_RANGE.ordinal()] + 3);
        range = Math.min(64, range);
        boolean attack = row[ATTACK_THIS_TICK.ordinal()] != 0;
        for (double eye : eyes) {
            Vector origin = new Vector(x, y + eye, z);
            distance = Math.min(distance, distance(box, origin));
            for (Vector direction : directions) {
                Vector centerDelta = box.getCenter().subtract(origin);
                double currentAngle = angle(direction, centerDelta);
                if (currentAngle < centerAngle) {
                    centerAngle = currentAngle;
                    centerDistance = centerDelta.length();
                }
                angle = Math.min(angle, currentAngle);
                for (int corner = 0; corner < 8; corner++)
                    angle = Math.min(angle, angle(direction, corner(box, corner).subtract(origin)));
                if (!attack) continue;
                var intercept = box.rayTrace(origin, direction, range);
                if (box.contains(origin)) {
                    hits++;
                    if (hit == null) hit = origin;
                    miss = 0;
                } else if (intercept != null) {
                    hits++;
                    if (hit == null) hit = intercept.getHitPosition();
                    miss = 0;
                } else if (miss != 0) {
                    miss = Math.min(miss, missDistance(box, origin, direction, range));
                }
            }
        }
        row[TARGET_DISTANCE.ordinal()] = (float) distance;
        row[TARGET_AIM_ANGLE.ordinal()] = (float) angle;
        double radius = Math.toDegrees(Math.atan2(Math.max(box.getWidthX(), Math.max(box.getHeight(), box.getWidthZ())) / 2, centerDistance));
        row[AIM_ERROR.ordinal()] = radius > 1e-6 ? (float) (centerAngle / radius) : 0;
        if (!attack) return;
        row[RAYTRACE_HIT.ordinal()] = hit == null ? 0 : 1;
        row[RAYTRACE_HIT_STRICT.ordinal()] = hit == null ? 0 : 1;
        row[RAYTRACE_DIRS_HIT_COUNT.ordinal()] = hits;
        row[RAYTRACE_MISS_DISTANCE.ordinal()] = (float) miss;
        if (hit != null) {
            Vector relative = hit.clone().subtract(box.getCenter());
            row[HIT_POINT_REL_X.ordinal()] = (float) relative.getX();
            row[HIT_POINT_REL_Y.ordinal()] = (float) relative.getY();
            row[HIT_POINT_REL_Z.ordinal()] = (float) relative.getZ();
        }
        Vector eye = new Vector(x, y + player.getEyeHeight(), z);
        Vector delta = box.getCenter().subtract(eye);
        double horizontal = Math.hypot(delta.getX(), delta.getZ());
        double desiredYaw = Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ()));
        double yawError = Math.IEEEremainder(yaw - desiredYaw, 360);
        double pitchError = pitch + Math.toDegrees(Math.atan2(delta.getY(), horizontal));
        double width = Math.toDegrees(Math.atan2(Math.max(box.getWidthX(), box.getWidthZ()) / 2, delta.length()));
        double height = Math.toDegrees(Math.atan2(box.getHeight() / 2, delta.length()));
        row[AIM_ERROR_YAW.ordinal()] = width > 1e-6 ? (float) (yawError / width) : 0;
        row[AIM_ERROR_PITCH.ordinal()] = height > 1e-6 ? (float) (pitchError / height) : 0;
        Vector standingEye = new Vector(x, y + 1.62, z);
        Boolean centerOccluded = occluded(player, standingEye, box.getCenter());
        row[ATTACK_RAY_OCCLUDED.ordinal()] = centerOccluded == null ? UNAVAILABLE : centerOccluded ? 1 : 0;
        int visible = Boolean.FALSE.equals(centerOccluded) ? 1 : 0;
        boolean complete = centerOccluded != null;
        for (int i = 0; i < 8; i++) {
            Boolean occluded = occluded(player, standingEye, corner(box, i));
            complete &= occluded != null;
            if (Boolean.FALSE.equals(occluded)) visible++;
        }
        row[EXPOSURE_FRACTION.ordinal()] = complete ? visible / 9F : UNAVAILABLE;
        boolean blind = player.getActivePotionEffects().stream()
                .anyMatch(effect -> effect.getType().getKey().getKey().equals("blindness"));
        row[IS_CRITICAL.ordinal()] = row[ATTACK_COOLDOWN_PROGRESS.ordinal()] > 0.9
                && player.getFallDistance() > 0 && row[ON_GROUND.ordinal()] == 0
                && row[IS_CLIMBING.ordinal()] == 0 && !player.isInWater() && !player.isInsideVehicle()
                && !player.isSprinting() && !blind && target.living() ? 1 : 0;
    }

    public static Vector look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    private static double angle(Vector direction, Vector delta) {
        if (delta.lengthSquared() < 1e-6) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, direction.dot(delta) / delta.length()))));
    }

    private static Vector corner(BoundingBox box, int index) {
        return new Vector((index & 1) == 0 ? box.getMinX() : box.getMaxX(),
                (index & 2) == 0 ? box.getMinY() : box.getMaxY(),
                (index & 4) == 0 ? box.getMinZ() : box.getMaxZ());
    }

    public static double distance(BoundingBox box, Vector point) {
        double dx = Math.max(0, Math.max(box.getMinX() - point.getX(), point.getX() - box.getMaxX()));
        double dy = Math.max(0, Math.max(box.getMinY() - point.getY(), point.getY() - box.getMaxY()));
        double dz = Math.max(0, Math.max(box.getMinZ() - point.getZ(), point.getZ() - box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double missDistance(BoundingBox box, Vector origin, Vector direction, double range) {
        double low = 0, high = range;
        for (int i = 0; i < 24; i++) {
            double a = low + (high - low) / 3, b = high - (high - low) / 3;
            if (distance(box, origin.clone().add(direction.clone().multiply(a)))
                    < distance(box, origin.clone().add(direction.clone().multiply(b)))) high = b;
            else low = a;
        }
        return distance(box, origin.clone().add(direction.clone().multiply((low + high) / 2)));
    }

    private static Boolean occluded(Player player, Vector from, Vector to) {
        double length = from.distance(to);
        if (length > 12) return null;
        int steps = (int) Math.ceil(length / 0.2);
        for (int i = 1; i < steps; i++) {
            double ratio = (double) i / steps;
            int x = (int) Math.floor(from.getX() + (to.getX() - from.getX()) * ratio);
            int y = (int) Math.floor(from.getY() + (to.getY() - from.getY()) * ratio);
            int z = (int) Math.floor(from.getZ() + (to.getZ() - from.getZ()) * ratio);
            var block = TelemetryState.safeBlock(player, x, y, z);
            if (block == null) return null;
            if (block.getType().isSolid()) return true;
        }
        return false;
    }
}
