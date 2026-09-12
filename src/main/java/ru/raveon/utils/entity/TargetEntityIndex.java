package ru.raveon.utils.entity;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TargetEntityIndex {
    private final Map<Integer, UUID> entityIdToUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> uuidToEntity = new ConcurrentHashMap<>();
    private final Map<String, TargetEntityAdapter> adapters = new ConcurrentHashMap<>();
    private volatile TargetEntityAdapter[] adapterSnapshot = new TargetEntityAdapter[0];

    public TargetEntityIndex() {
        registerAdapter(VanillaTargetEntityAdapter.INSTANCE);
    }

    public void initialize() {
        clear();
        Bukkit.getWorlds().forEach(this::trackWorld);
        Bukkit.getOnlinePlayers().forEach(this::track);
    }

    public void clear() {
        entityIdToUuid.clear();
        uuidToEntity.clear();
    }

    public void registerAdapter(@NotNull TargetEntityAdapter adapter) {
        adapters.put(adapter.getKey().toLowerCase(), adapter);
        rebuildSnapshot();
        reindexAll();
    }

    public void unregisterAdapter(@NotNull String key) {
        if (adapters.remove(key.toLowerCase()) != null) {
            rebuildSnapshot();
            reindexAll();
        }
    }

    public boolean hasAdapter(@NotNull String key) {
        return adapters.containsKey(key.toLowerCase());
    }

    public Collection<TargetEntityAdapter> getAdapters() {
        return Collections.unmodifiableCollection(adapters.values());
    }

    public void reindexAll() {
        clear();
        initialize();
    }

    public void trackWorld(@NotNull World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            trackChunk(chunk);
        }
    }

    public void trackChunk(@NotNull Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            track(entity);
        }
    }

    public void untrackChunk(@NotNull Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            untrack(entity);
        }
    }

    public void track(@Nullable Entity entity) {
        if (!isTrackable(entity)) {
            return;
        }

        uuidToEntity.put(entity.getUniqueId(), entity);
        entityIdToUuid.put(entity.getEntityId(), entity.getUniqueId());
    }

    public void untrack(@Nullable Entity entity) {
        if (entity == null) {
            return;
        }

        UUID uuid = entity.getUniqueId();
        uuidToEntity.remove(uuid);
        entityIdToUuid.remove(entity.getEntityId());

        UUID mappedUuid = entityIdToUuid.get(entity.getEntityId());
        if (uuid.equals(mappedUuid)) {
            entityIdToUuid.remove(entity.getEntityId());
        }
    }

    public @Nullable Entity getByEntityId(int entityId) {
        UUID uuid = entityIdToUuid.get(entityId);
        if (uuid == null) {
            return null;
        }

        Entity entity = uuidToEntity.get(uuid);
        if (!isUsable(entity, entityId, uuid)) {
            remove(entityId, uuid);
            return null;
        }

        return entity;
    }

    public @Nullable Entity getByUniqueId(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }

        Entity entity = uuidToEntity.get(uuid);
        if (entity != null && isUsable(entity, null, uuid)) {
            return entity;
        }

        Entity resolved = Bukkit.getEntity(uuid);
        if (!isTrackable(resolved)) {
            uuidToEntity.remove(uuid);
            return null;
        }

        uuidToEntity.put(uuid, resolved);
        entityIdToUuid.put(resolved.getEntityId(), uuid);
        return resolved;
    }

    public boolean isTracked(int entityId) {
        return getByEntityId(entityId) != null;
    }

    public boolean isTracked(@Nullable UUID uuid) {
        return getByUniqueId(uuid) != null;
    }

    public Collection<Entity> getTrackedEntities() {
        ArrayList<Entity> entities = new ArrayList<>(uuidToEntity.size());
        for (Entity entity : uuidToEntity.values()) {
            if (isUsable(entity, null, entity.getUniqueId())) {
                entities.add(entity);
            }
        }
        return Collections.unmodifiableList(entities);
    }

    private boolean isTrackable(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }

        TargetEntityAdapter[] localSnapshot = adapterSnapshot;
        for (TargetEntityAdapter adapter : localSnapshot) {
            if (adapter.shouldIndex(entity)) {
                return true;
            }
        }

        return false;
    }

    private boolean isUsable(@Nullable Entity entity, @Nullable Integer expectedEntityId, @NotNull UUID expectedUuid) {
        if (entity == null) {
            return false;
        }

        if (!entity.isValid()) {
            return false;
        }

        if (!expectedUuid.equals(entity.getUniqueId())) {
            return false;
        }

        if (expectedEntityId != null && entity.getEntityId() != expectedEntityId) {
            return false;
        }

        return isTrackable(entity);
    }

    private void remove(int entityId, @NotNull UUID uuid) {
        entityIdToUuid.remove(entityId);
        uuidToEntity.remove(uuid);
    }

    private void rebuildSnapshot() {
        this.adapterSnapshot = adapters.values().stream()
                .sorted(Comparator.comparingInt(TargetEntityAdapter::getPriority).reversed())
                .toArray(TargetEntityAdapter[]::new);
    }
}