package ru.raveon.utils.entity;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.UUID;

public record EntitySnapshot(
        UUID uuid, UUID worldId, int entityId,
        double x, double y, double z,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        boolean valid, boolean living, boolean spectator, long capturedNanos,
        String type, int ageTicks, UUID attachedTo) {

    private static final boolean FIREWORK_ATTACHMENT_SUPPORTED = detectFireworkAttachment();

    public static EntitySnapshot capture(Entity entity) {
        Location location = entity.getLocation();
        BoundingBox box = entity.getBoundingBox();
        return new EntitySnapshot(
                entity.getUniqueId(), entity.getWorld().getUID(), entity.getEntityId(),
                location.getX(), location.getY(), location.getZ(),
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ(),
                entity.isValid() && !entity.isDead(),
                entity instanceof LivingEntity,
                entity instanceof Player player && player.getGameMode() == GameMode.SPECTATOR,
                System.nanoTime(),
                entity instanceof Player ? "PLAYER" : entity.getType() == null ? "UNKNOWN" : entity.getType().name(),
                entity.getTicksLived(), attachedPlayer(entity));
    }

    public static boolean supportsFireworkAttachment() {
        return FIREWORK_ATTACHMENT_SUPPORTED;
    }

    private static boolean detectFireworkAttachment() {
        try {
            org.bukkit.entity.Firework.class.getMethod("getAttachedTo");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static UUID attachedPlayer(Entity entity) {
        if (!FIREWORK_ATTACHMENT_SUPPORTED || !(entity instanceof org.bukkit.entity.Firework firework)) return null;
        try {
            LivingEntity attached = firework.getAttachedTo();
            return attached == null ? null : attached.getUniqueId();
        } catch (NoSuchMethodError ignored) {
            return null;
        }
    }

    public BoundingBox boundingBox() {
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public double distanceSquared(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
