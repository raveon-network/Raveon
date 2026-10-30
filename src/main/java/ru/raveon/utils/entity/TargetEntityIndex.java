package ru.raveon.utils.entity;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import ru.raveon.Raveon;
import ru.raveon.utils.SchedulerUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TargetEntityIndex {
    private static final long REFRESH_NANOS = 25_000_000L;
    private static final long DEMAND_IDLE_NANOS = 250_000_000L;
    private static final long MAX_AGE_NANOS = 75_000_000L;
    private static final long CACHE_IDLE_NANOS = 5_000_000_000L;
    private static final int MAX_CACHED_CHUNKS = 2048;

    private final Map<Integer, TargetEntry> entitiesById = new ConcurrentHashMap<>();
    private final Map<UUID, TargetEntry> targets = new ConcurrentHashMap<>();
    private final Map<ChunkKey, ChunkEntry> chunks = new ConcurrentHashMap<>();

    public void initialize() {
        clear();
        for (World world : Bukkit.getWorlds()) trackWorld(world);
    }

    public void clear() {
        entitiesById.clear();
        targets.clear();
        chunks.clear();
    }

    public void trackWorld(@NotNull World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            int x = chunk.getX();
            int z = chunk.getZ();
            SchedulerUtils.runRegion(Raveon.INSTANCE, world, x, z, () -> {
                if (world.isChunkLoaded(x, z)) trackChunk(world.getChunkAt(x, z));
            });
        }
    }

    public void trackChunk(@NotNull Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (SchedulerUtils.isOwned(entity)) track(entity);
        }
    }

    public void untrackChunk(@NotNull Chunk chunk) {
        for (Entity entity : chunk.getEntities()) untrack(entity);
        chunks.remove(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    public void track(@Nullable Entity entity) {
        if (entity == null || !supported(entity)) return;
        UUID uuid = entity.getUniqueId();
        int entityId = entity.getEntityId();
        TargetEntry entry = new TargetEntry(entity, uuid, entityId);
        entry.snapshot = EntitySnapshot.capture(entity);
        TargetEntry previous = targets.put(uuid, entry);
        if (previous != null && previous.entityId != entityId)
            entitiesById.remove(previous.entityId, previous);
        entitiesById.put(entityId, entry);
    }

    public void untrack(@Nullable Entity entity) {
        if (entity == null) return;
        UUID uuid = entity.getUniqueId();
        TargetEntry entry = targets.get(uuid);
        if (entry != null && entry.entity.get() == entity && targets.remove(uuid, entry))
            entitiesById.remove(entry.entityId, entry);
    }

    public void untrackWorld(UUID worldId) {
        targets.entrySet().removeIf(entry -> {
            EntitySnapshot snapshot = entry.getValue().snapshot;
            if (snapshot != null && worldId.equals(snapshot.worldId())) {
                entitiesById.remove(entry.getValue().entityId, entry.getValue());
                return true;
            }
            return false;
        });
        chunks.keySet().removeIf(key -> key.worldId.equals(worldId));
    }

    public @Nullable UUID getUniqueIdByEntityId(int entityId) {
        TargetEntry entry = entitiesById.get(entityId);
        if (entry == null) return null;
        if (targets.get(entry.uuid) != entry || entry.entity.get() == null) {
            entitiesById.remove(entityId, entry);
            targets.remove(entry.uuid, entry);
            return null;
        }
        return entry.uuid;
    }

    public @Nullable EntitySnapshot getSnapshot(@Nullable UUID uuid) {
        if (uuid == null) return null;
        TargetEntry entry = targets.get(uuid);
        if (entry == null) return null;
        Entity entity = entry.entity.get();
        if (entity == null) {
            targets.remove(uuid, entry);
            entitiesById.remove(entry.entityId, entry);
            return null;
        }
        if (SchedulerUtils.isOwned(entity)) {
            EntitySnapshot snapshot = EntitySnapshot.capture(entity);
            if (!snapshot.valid()) {
                untrack(entity);
                return null;
            }
            entry.snapshot = snapshot;
            return snapshot;
        }
        long now = System.nanoTime();
        entry.lastRequest = now;
        EntitySnapshot previous = entry.snapshot;
        if (entry.pending.compareAndSet(false, true)) scheduleTargetRefresh(uuid, entry, entity);
        return previous != null && now - previous.capturedNanos() <= MAX_AGE_NANOS
                ? previous : null;
    }

    private void scheduleTargetRefresh(UUID uuid, TargetEntry entry, Entity entity) {
        try {
            SchedulerUtils.runEntity(Raveon.INSTANCE, entity, () -> {
                if (targets.get(uuid) != entry
                        || System.nanoTime() - entry.lastRequest > DEMAND_IDLE_NANOS) {
                    entry.pending.set(false);
                    return;
                }
                boolean again = false;
                try {
                    EntitySnapshot snapshot = EntitySnapshot.capture(entity);
                    if (snapshot.valid()) {
                        entry.snapshot = snapshot;
                        again = true;
                    } else {
                        untrack(entity);
                    }
                } finally {
                    if (!again) entry.pending.set(false);
                }
                if (again) scheduleTargetRefresh(uuid, entry, entity);
            }, () -> {
                entry.pending.set(false);
                targets.remove(uuid, entry);
                entitiesById.remove(entry.entityId, entry);
            });
        } catch (RuntimeException exception) {
            entry.pending.set(false);
            throw exception;
        }
    }

    public Integer countNearbyTargets(Player player) {
        Location center = player.getLocation();
        if (!SchedulerUtils.isFolia()) {
            int count = 0;
            for (Entity entity : player.getNearbyEntities(6.0, 6.0, 6.0)) {
                EntitySnapshot snapshot = EntitySnapshot.capture(entity);
                if (qualifies(snapshot, player.getUniqueId(), center)) count++;
            }
            return count;
        }
        World world = player.getWorld();
        long now = System.nanoTime();
        Map<UUID, EntitySnapshot> neighbors = new HashMap<>();
        boolean ready = true;
        for (int x = floorChunk(center.getX() - 6.0); x <= floorChunk(center.getX() + 6.0); x++) {
            for (int z = floorChunk(center.getZ() - 6.0); z <= floorChunk(center.getZ() + 6.0); z++) {
                ChunkSnapshot snapshot = requestChunk(world, x, z, now);
                if (snapshot == null) {
                    ready = false;
                    continue;
                }
                for (EntitySnapshot entity : snapshot.entities) {
                    neighbors.merge(entity.uuid(), entity,
                            (a, b) -> a.capturedNanos() >= b.capturedNanos() ? a : b);
                }
            }
        }
        if (!ready) return null;
        int count = 0;
        for (EntitySnapshot entity : neighbors.values())
            if (qualifies(entity, player.getUniqueId(), center)) count++;
        return count;
    }

    public List<EntitySnapshot> nearbySnapshots(Player player, double radius) {
        if (radius < 0 || radius > 8.0) throw new IllegalArgumentException("Unsupported snapshot radius");
        Location center = player.getLocation();
        World world = player.getWorld();
        long now = System.nanoTime();
        Map<UUID, EntitySnapshot> neighbors = new HashMap<>();
        boolean ready = true;
        for (int x = floorChunk(center.getX() - radius); x <= floorChunk(center.getX() + radius); x++) {
            for (int z = floorChunk(center.getZ() - radius); z <= floorChunk(center.getZ() + radius); z++) {
                ChunkSnapshot snapshot = requestChunk(world, x, z, now);
                if (snapshot == null) {
                    ready = false;
                    continue;
                }
                for (EntitySnapshot entity : snapshot.entities) {
                    if (!entity.uuid().equals(player.getUniqueId()) && entity.valid()
                            && entity.distanceSquared(center.getX(), center.getY(), center.getZ()) <= radius * radius)
                        neighbors.merge(entity.uuid(), entity,
                                (a, b) -> a.capturedNanos() >= b.capturedNanos() ? a : b);
                }
            }
        }
        return ready ? List.copyOf(neighbors.values()) : null;
    }

    private ChunkSnapshot requestChunk(World world, int x, int z, long now) {
        ChunkKey key = new ChunkKey(world.getUID(), x, z);
        ChunkEntry entry = chunks.get(key);
        if (entry == null) {
            synchronized (chunks) {
                entry = chunks.get(key);
                if (entry == null) {
                    if (chunks.size() >= MAX_CACHED_CHUNKS)
                        chunks.entrySet().removeIf(cached ->
                                now - cached.getValue().lastRequest > CACHE_IDLE_NANOS
                                        && !cached.getValue().pending.get());
                    if (chunks.size() >= MAX_CACHED_CHUNKS) return null;
                    entry = new ChunkEntry();
                    chunks.put(key, entry);
                }
            }
        }
        entry.lastRequest = now;
        ChunkSnapshot previous = entry.snapshot;
        if (SchedulerUtils.isOwned(world, x, z)) {
            if (previous == null || now - previous.capturedNanos >= REFRESH_NANOS) {
                ChunkSnapshot refreshed = captureChunk(world, x, z);
                if (refreshed != null) entry.snapshot = previous = refreshed;
            }
        } else if (entry.pending.compareAndSet(false, true)) {
            scheduleChunkRefresh(key, entry, world, x, z);
        }
        return previous != null && now - previous.capturedNanos <= MAX_AGE_NANOS ? previous : null;
    }

    private void scheduleChunkRefresh(ChunkKey key, ChunkEntry entry, World world, int x, int z) {
        try {
            SchedulerUtils.runRegion(Raveon.INSTANCE, world, x, z, () -> {
                if (chunks.get(key) != entry
                        || System.nanoTime() - entry.lastRequest > DEMAND_IDLE_NANOS) {
                    entry.pending.set(false);
                    return;
                }
                boolean again = false;
                try {
                    ChunkSnapshot refreshed = captureChunk(world, x, z);
                    if (refreshed != null) entry.snapshot = refreshed;
                    again = true;
                } finally {
                    if (!again) entry.pending.set(false);
                }
                if (again) scheduleChunkRefresh(key, entry, world, x, z);
            });
        } catch (RuntimeException exception) {
            entry.pending.set(false);
            throw exception;
        }
    }

    private ChunkSnapshot captureChunk(World world, int x, int z) {
        List<EntitySnapshot> snapshots = new ArrayList<>();
        if (world.isChunkLoaded(x, z)) {
            for (Entity entity : world.getChunkAt(x, z).getEntities()) {
                if (!SchedulerUtils.isOwned(entity)) return null;
                if (entity instanceof org.bukkit.entity.LivingEntity
                        || entity instanceof org.bukkit.entity.EnderCrystal
                        || entity instanceof org.bukkit.entity.Vehicle
                        || entity instanceof org.bukkit.entity.Firework)
                    snapshots.add(EntitySnapshot.capture(entity));
            }
        }
        return new ChunkSnapshot(List.copyOf(snapshots), System.nanoTime());
    }

    private static int floorChunk(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    private static boolean qualifies(EntitySnapshot entity, UUID playerId, Location center) {
        return entity.valid() && entity.living() && !entity.spectator()
                && !entity.uuid().equals(playerId) && entity.worldId().equals(center.getWorld().getUID())
                && entity.distanceSquared(center.getX(), center.getY(), center.getZ()) <= 36.0;
    }

    private static boolean supported(Entity entity) {
        return entity instanceof Player || "minecraft:mannequin".equals(entity.getType().getKey().toString());
    }

    private static final class TargetEntry {
        private final WeakReference<Entity> entity;
        private final UUID uuid;
        private final int entityId;
        private final AtomicBoolean pending = new AtomicBoolean();
        private volatile EntitySnapshot snapshot;
        private volatile long lastRequest;

        private TargetEntry(Entity entity, UUID uuid, int entityId) {
            this.entity = new WeakReference<>(entity);
            this.uuid = uuid;
            this.entityId = entityId;
        }
    }

    private static final class ChunkEntry {
        private final AtomicBoolean pending = new AtomicBoolean();
        private volatile ChunkSnapshot snapshot;
        private volatile long lastRequest;
    }

    private record ChunkKey(UUID worldId, int x, int z) {}
    private record ChunkSnapshot(List<EntitySnapshot> entities, long capturedNanos) {}
}
