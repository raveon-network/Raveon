package ru.raveon.checks.data;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import ru.raveon.api.models.UltraFrame;
import ru.raveon.utils.math.MouseCalculator;
import ru.raveon.utils.entity.EntitySnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

public final class UltraState {
    private float lastYaw = 0.0F;
    private float lastPitch = 0.0F;
    private float lastDeltaYaw = 0.0F;
    private float lastDeltaPitch = 0.0F;
    private float lastAccelYaw = 0.0F;
    private float lastAccelPitch = 0.0F;
    private static final double[] POSSIBLE_EYE_HEIGHTS = new double[] {1.62, 1.27, 0.4};
    private static final double MINIMUM_DIVISOR =
            (float) Math.pow(0.2F, 3.0) * 8.0 * 0.15 - 0.001;
    private final Object stateLock = new Object();
    private final ArrayDeque<Double> xRotModeList = new ArrayDeque<>(80);
    private final Map<Double, Integer> xRotModePopularity = new HashMap<>();
    private final ArrayDeque<Double> yRotModeList = new ArrayDeque<>(80);
    private final Map<Double, Integer> yRotModePopularity = new HashMap<>();
    private double xRotModeValue;
    private int xRotModeCount;
    private double yRotModeValue;
    private int yRotModeCount;
    private double lastAcceptedYawDelta;
    private double lastAcceptedPitchDelta;
    private double yawMode;
    private double pitchMode;
    private double yawDeltaDots;
    private double pitchDeltaDots;
    private boolean rotationOnPreviousFlying = true;
    private boolean idleFlyingSinceLastFrame;
    private UUID targetId;

    public UUID targetId() {
        synchronized (this.stateLock) {
            return this.targetId;
        }
    }

    public UltraFrame capture(Player bukkitPlayer, EntitySnapshot target, int nearbyTargets, float rawYaw, float rawPitch) {
        synchronized (this.stateLock) {
            float currentYaw = MouseCalculator.normalizeAngle(rawYaw);
            float currentPitch = Math.max(-90.0F, Math.min(90.0F, rawPitch));
            float deltaYaw = MouseCalculator.normalizeAngle(currentYaw - this.lastYaw);
            float deltaPitch = currentPitch - this.lastPitch;
            float accelYaw = MouseCalculator.calculateAcceleration(deltaYaw, this.lastDeltaYaw);
            float accelPitch =
                    MouseCalculator.calculateAcceleration(deltaPitch, this.lastDeltaPitch);
            float jerkYaw = MouseCalculator.calculateJerk(accelYaw, this.lastAccelYaw);
            float jerkPitch = MouseCalculator.calculateJerk(accelPitch, this.lastAccelPitch);
            float gcdErrorYaw = MouseCalculator.calculateGCDError(deltaYaw, this.lastDeltaYaw);
            float gcdErrorPitch =
                    MouseCalculator.calculateGCDError(deltaPitch, this.lastDeltaPitch);
            float[] contextFeatures =
                    this.captureContextFeatures(
                            bukkitPlayer,
                            target,
                            nearbyTargets,
                            this.lastYaw,
                            this.lastPitch,
                            currentYaw,
                            currentPitch,
                            deltaYaw,
                            deltaPitch);
            this.lastYaw = currentYaw;
            this.lastPitch = currentPitch;
            this.lastDeltaYaw = deltaYaw;
            this.lastDeltaPitch = deltaPitch;
            this.lastAccelYaw = accelYaw;
            this.lastAccelPitch = accelPitch;
            return new UltraFrame(
                    deltaYaw,
                    deltaPitch,
                    accelYaw,
                    accelPitch,
                    jerkYaw,
                    jerkPitch,
                    gcdErrorYaw,
                    gcdErrorPitch,
                    contextFeatures);
        }
    }

    public void onFlying(boolean hasRotation) {
        Object object = this.stateLock;
        synchronized (object) {
            if (!hasRotation) {
                this.idleFlyingSinceLastFrame = true;
            }
        }
    }

    public void onAttack(UUID targetUuid) {
        synchronized (this.stateLock) {
            this.targetId = targetUuid;
        }
    }

    public void resetAimState() {
        Object object = this.stateLock;
        synchronized (object) {
            this.xRotModeList.clear();
            this.xRotModePopularity.clear();
            this.yRotModeList.clear();
            this.yRotModePopularity.clear();
            this.xRotModeValue = 0.0;
            this.xRotModeCount = 0;
            this.yRotModeValue = 0.0;
            this.yRotModeCount = 0;
            this.lastAcceptedYawDelta = 0.0;
            this.lastAcceptedPitchDelta = 0.0;
            this.yawMode = 0.0;
            this.pitchMode = 0.0;
            this.yawDeltaDots = 0.0;
            this.pitchDeltaDots = 0.0;
            this.targetId = null;
        }
    }

    private float[] captureContextFeatures(
            Player bukkitPlayer,
            EntitySnapshot target,
            int nearbyTargets,
            float fromYaw,
            float fromPitch,
            float yaw,
            float pitch,
            float deltaYaw,
            float deltaPitch) {
        synchronized (this.stateLock) {
            float[] f = new float[UltraFrame.CONTEXT_FEATURE_COUNT];
            f[0] =
                    bool(
                            this.rotationOnPreviousFlying
                                    && !this.idleFlyingSinceLastFrame);
            this.rotationOnPreviousFlying = !this.idleFlyingSinceLastFrame;
            this.idleFlyingSinceLastFrame = false;
            f[1] = pitch;
            this.processAim(fromYaw, yaw, deltaYaw, deltaPitch);
            boolean modeYawValid = this.xRotModeCount > 15;
            boolean modePitchValid = this.yRotModeCount > 15;
            f[2] = (float) this.yawMode;
            f[3] = (float) this.pitchMode;
            f[4] = bool(modeYawValid);
            f[5] = bool(modePitchValid);
            f[6] = modeYawValid ? (float) this.yawDeltaDots : 0.0F;
            f[7] = modePitchValid ? (float) this.pitchDeltaDots : 0.0F;
            boolean targetValid =
                    target != null
                            && target.valid()
                            && target.worldId().equals(bukkitPlayer.getWorld().getUID());
            f[8] = bool(targetValid);
            if (targetValid) {
                this.captureTargetFeatures(f, bukkitPlayer, target, nearbyTargets, fromYaw, fromPitch, yaw, pitch);
            }

            f[14] = classifyItem(bukkitPlayer.getInventory().getItemInMainHand());
            f[15] = readPing(bukkitPlayer);
            return f;
        }
    }

    private void processAim(float fromYaw, float toYaw, float deltaYaw, float deltaPitch) {
        double deltaYawAbs = Math.abs(deltaYaw);
        double deltaPitchAbs = Math.abs(deltaPitch);
        double divisorX = rotationGcd(deltaYawAbs, this.lastAcceptedYawDelta);
        double yawUlp;
        if (deltaYawAbs > 0.0
                && deltaYawAbs < 5.0
                && divisorX > MINIMUM_DIVISOR
                && (yawUlp = Math.ulp(Math.max(Math.abs(fromYaw), Math.abs(toYaw))))
                        < divisorX * 0.0625) {
            if (this.lastAcceptedYawDelta != 0.0) {
                runningModeAdd(
                        this.xRotModeList,
                        this.xRotModePopularity,
                        divisorX,
                        Math.max(0.001, yawUlp * 2.0));
            }

            this.lastAcceptedYawDelta = deltaYawAbs;
        }

        double divisorY = rotationGcd(deltaPitchAbs, this.lastAcceptedPitchDelta);
        if (deltaPitchAbs > 0.0 && deltaPitchAbs < 5.0 && divisorY > MINIMUM_DIVISOR) {
            if (this.lastAcceptedPitchDelta != 0.0) {
                runningModeAdd(this.yRotModeList, this.yRotModePopularity, divisorY, 0.001);
            }

            this.lastAcceptedPitchDelta = deltaPitchAbs;
        }

        if (this.xRotModeList.size() > 15) {
            double[] mode = runningModeUpdate(this.xRotModePopularity);
            this.xRotModeValue = mode[0];
            this.xRotModeCount = (int) mode[1];
            if (this.xRotModeCount > 15) {
                this.yawMode = this.xRotModeValue;
            }
        }

        if (this.yRotModeList.size() > 15) {
            double[] mode = runningModeUpdate(this.yRotModePopularity);
            this.yRotModeValue = mode[0];
            this.yRotModeCount = (int) mode[1];
            if (this.yRotModeCount > 15) {
                this.pitchMode = this.yRotModeValue;
            }
        }

        if (this.yawMode > 0.0) {
            this.yawDeltaDots = deltaYawAbs / this.yawMode;
        }

        if (this.pitchMode > 0.0) {
            this.pitchDeltaDots = deltaPitchAbs / this.pitchMode;
        }
    }

    private static void runningModeAdd(
            ArrayDeque<Double> list,
            Map<Double, Integer> popularity,
            double value,
            double bucketWidth) {
        Double type;
        if (list.size() >= 80 && (type = list.poll()) != null) {
            Integer count = popularity.get(type);
            if (count != null && count != 1) {
                popularity.put(type, count - 1);
            } else {
                popularity.remove(type);
            }
        }

        for (Entry<Double, Integer> entry : popularity.entrySet()) {
            if (Math.abs(entry.getKey() - value) < bucketWidth) {
                entry.setValue(entry.getValue() + 1);
                list.add(entry.getKey());
                return;
            }
        }

        popularity.put(value, 1);
        list.add(value);
    }

    private static double[] runningModeUpdate(Map<Double, Integer> popularity) {
        int max = 0;
        double mostPopular = 0.0;

        for (Entry<Double, Integer> entry : popularity.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                mostPopular = entry.getKey();
            }
        }

        return new double[] {mostPopular, max};
    }

    private static double rotationGcd(double aInput, double bInput) {
        if (aInput == 0.0) {
            return 0.0;
        } else {
            double a = aInput;
            double b = bInput;
            if (aInput < bInput) {
                a = bInput;
                b = aInput;
            }

            while (b > MINIMUM_DIVISOR) {
                double temp = a - Math.floor(a / b) * b;
                a = b;
                b = temp;
            }

            return a;
        }
    }

    private void captureTargetFeatures(
            float[] f,
            Player bukkitPlayer,
            EntitySnapshot target,
            int nearbyTargets,
            float fromYaw,
            float fromPitch,
            float yaw,
            float pitch) {
        Location feet = bukkitPlayer.getLocation();
        double px = feet.getX();
        double py = feet.getY();
        double pz = feet.getZ();
        BoundingBox box = target.boundingBox();
        List<Vector> lookDirs = possibleLookDirs(bukkitPlayer, fromYaw, fromPitch, yaw, pitch);
        f[9] = (float) minAngleToBox(px, py, pz, lookDirs, box);
        f[10] = (float) (target.x() - px);
        f[11] = (float) (target.y() - py);
        f[12] = (float) (target.z() - pz);
        f[13] = nearbyTargets;
    }

    private static List<Vector> possibleLookDirs(
            Player bukkitPlayer, float lastYaw, float lastPitch, float yaw, float pitch) {
        ClientVersion version = clientVersion(bukkitPlayer);
        ArrayList<Vector> dirs = new ArrayList<>(3);
        dirs.add(lookVector(yaw, pitch));
        if (version != null && version.isOlderThan(ClientVersion.V_1_8)) {
            return dirs;
        } else {
            dirs.add(lookVector(lastYaw, pitch));
            if (version == null || version.isNewerThanOrEquals(ClientVersion.V_1_9)) {
                dirs.add(lookVector(lastYaw, lastPitch));
            }

            return dirs;
        }
    }

    private static Vector lookVector(float yaw, float pitch) {
        double pitchRad = Math.toRadians(pitch);
        double yawRad = Math.toRadians(-yaw);
        double pitchCos = Math.cos(pitchRad);
        Vector look =
                new Vector(
                        Math.sin(yawRad) * pitchCos,
                        -Math.sin(pitchRad),
                        Math.cos(yawRad) * pitchCos);
        double length = look.length();
        return length == 0.0 ? look : look.multiply(1.0 / length);
    }

    private static double minAngleToBox(
            double px, double py, double pz, List<Vector> lookDirs, BoundingBox box) {
        double[][] points =
                new double[][] {
                    {box.getCenterX(), box.getCenterY(), box.getCenterZ()},
                    {box.getMinX(), box.getMinY(), box.getMinZ()},
                    {box.getMaxX(), box.getMinY(), box.getMinZ()},
                    {box.getMinX(), box.getMaxY(), box.getMinZ()},
                    {box.getMaxX(), box.getMaxY(), box.getMinZ()},
                    {box.getMinX(), box.getMinY(), box.getMaxZ()},
                    {box.getMaxX(), box.getMinY(), box.getMaxZ()},
                    {box.getMinX(), box.getMaxY(), box.getMaxZ()},
                    {box.getMaxX(), box.getMaxY(), box.getMaxZ()}
                };
        double minAngle = Double.MAX_VALUE;

        for (Vector lookVec : lookDirs) {
            for (double eye : POSSIBLE_EYE_HEIGHTS) {
                double ey = py + eye;

                for (double[] point : points) {
                    double dx = point[0] - px;
                    double dy = point[1] - ey;
                    double dz = point[2] - pz;
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (len < 0.001) {
                        return 0.0;
                    }

                    double dot =
                            lookVec.getX() * (dx / len)
                                    + lookVec.getY() * (dy / len)
                                    + lookVec.getZ() * (dz / len);
                    minAngle =
                            Math.min(
                                    minAngle,
                                    Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot)))));
                }
            }
        }

        return minAngle;
    }

    public static int classifyItem(ItemStack item) {
        if (item != null && !item.getType().isAir()) {
            Material type = item.getType();
            String name = type.name();
            switch (name) {
                case "END_CRYSTAL":
                    return 3;
                case "RESPAWN_ANCHOR":
                    return 5;
                case "GLOWSTONE":
                    return 6;
                case "TOTEM_OF_UNDYING":
                    return 7;
                case "SHIELD":
                    return 12;
                case "OBSIDIAN":
                case "CRYING_OBSIDIAN":
                case "BEDROCK":
                    return 4;
                case "BOW":
                case "CROSSBOW":
                case "TRIDENT":
                    return 9;
                case "ENDER_PEARL":
                case "SNOWBALL":
                case "EGG":
                    return 10;
                default:
                    name = type.name();
                    if (!name.endsWith("_SWORD")
                            && (!name.endsWith("_AXE") || name.endsWith("_PICKAXE"))) {
                        if (type.isEdible()) {
                            return 11;
                        } else {
                            return type.isBlock() ? 8 : 0;
                        }
                    } else {
                        return 2;
                    }
            }
        } else {
            return 1;
        }
    }

    private static float bool(boolean value) {
        return value ? 1.0F : 0.0F;
    }

    private static ClientVersion clientVersion(Player bukkitPlayer) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getClientVersion(bukkitPlayer);
        } catch (RuntimeException var2) {
            return null;
        }
    }

    private static float readPing(Player bukkitPlayer) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getPing(bukkitPlayer);
        } catch (RuntimeException var2) {
            return 0.0F;
        }
    }
}
