package ru.raveon.manager.analytic.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.raveon.utils.VersionHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketHologramLine {
    private final int entityId;
    private final UUID entityUuid;

    private final Set<UUID> spawnedViewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastTextByViewer = new ConcurrentHashMap<>();

    public PacketHologramLine(int entityId) {
        this.entityId = entityId;
        this.entityUuid = UUID.randomUUID();
    }

    public void spawn(Player viewer, Location location, String text) {
        UUID viewerId = viewer.getUniqueId();

        WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(entityUuid),
                EntityTypes.ARMOR_STAND,
                new Vector3d(location.getX(), location.getY(), location.getZ()),
                0.0F,
                0.0F,
                0.0F,
                0,
                Optional.empty()
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnPacket);

        spawnedViewers.add(viewerId);
        lastTextByViewer.remove(viewerId);

        updateText(viewer, text);
    }

    public void teleport(Player viewer, Location location) {
        if (!isSpawnedFor(viewer.getUniqueId())) {
            return;
        }

        WrapperPlayServerEntityTeleport teleportPacket = new WrapperPlayServerEntityTeleport(
                entityId,
                new Vector3d(location.getX(), location.getY(), location.getZ()),
                0.0F,
                0.0F,
                false
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleportPacket);
    }

    public void updateText(Player viewer, String text) {
        UUID viewerId = viewer.getUniqueId();

        if (!isSpawnedFor(viewerId)) {
            return;
        }

        String previousText = lastTextByViewer.get(viewerId);
        if (text.equals(previousText)) {
            return;
        }

        lastTextByViewer.put(viewerId, text);

        WrapperPlayServerEntityMetadata metadataPacket =
                new WrapperPlayServerEntityMetadata(entityId, createMetadata(text));

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadataPacket);
    }

    public void destroy(Player viewer) {
        UUID viewerId = viewer.getUniqueId();

        if (!spawnedViewers.remove(viewerId)) {
            return;
        }

        lastTextByViewer.remove(viewerId);

        PacketEvents.getAPI()
                .getPlayerManager()
                .sendPacket(viewer, new WrapperPlayServerDestroyEntities(entityId));
    }

    private boolean isSpawnedFor(UUID viewerId) {
        return spawnedViewers.contains(viewerId);
    }

    private List<EntityData<?>> createMetadata(String text) {
        List<EntityData<?>> metadata = new ArrayList<>(5);

        String coloredText = ChatColor.translateAlternateColorCodes('&', text);
        Component component = LegacyComponentSerializer.legacySection().deserialize(coloredText);
        String jsonComponent = AdventureSerializer.getGsonSerializer().serialize(component);

        metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20));
        metadata.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(jsonComponent)));
        metadata.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, true));
        metadata.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true));
        // ArmorStand flags moved from index 14 to 15 in the 1.21 protocol.
        int armorStandFlagsIndex = VersionHelper.CURRENT_VERSION >= 1210 ? 15 : 14;
        metadata.add(new EntityData<>(armorStandFlagsIndex, EntityDataTypes.BYTE, (byte) 0x10));

        return metadata;
    }
}
