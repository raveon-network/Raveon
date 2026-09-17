package ru.raveon.manager.analytic.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
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
import java.util.concurrent.atomic.AtomicInteger;

public final class PacketHologramLine {
    private static final byte BILLBOARD_CENTER = 3;
    private static final byte TEXT_FLAG_SEE_THROUGH = 0x02;

    /**
     * Fake entity ids are handed out downwards from the top of the int range, while the server
     * counts upwards from zero, so hologram lines never collide with each other or with real entities.
     */
    private static final AtomicInteger ENTITY_ID_ALLOCATOR = new AtomicInteger(Integer.MAX_VALUE);

    private final int entityId;
    private final UUID entityUuid;

    private final Set<UUID> spawnedViewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastTextByViewer = new ConcurrentHashMap<>();

    public PacketHologramLine() {
        this.entityId = ENTITY_ID_ALLOCATOR.getAndDecrement();
        this.entityUuid = UUID.randomUUID();
    }

    /**
     * Text displays (1.19.4+) can be mounted on the player with a translation offset,
     * so the client moves them together with the player. Older servers fall back to armor stands.
     */
    public static boolean usesDisplayEntities() {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19_4);
    }

    public int getEntityId() {
        return entityId;
    }

    /**
     * @param location  spawn location (target location for display entities, final line location for armor stands)
     * @param heightAboveFeet desired line height above the target's feet, used only for display entities
     */
    public void spawn(Player viewer, Location location, String text, double heightAboveFeet) {
        UUID viewerId = viewer.getUniqueId();
        boolean display = usesDisplayEntities();

        WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(entityUuid),
                display ? EntityTypes.TEXT_DISPLAY : EntityTypes.ARMOR_STAND,
                new Vector3d(location.getX(), location.getY(), location.getZ()),
                0.0F,
                0.0F,
                0.0F,
                0,
                Optional.empty()
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnPacket);

        if (display) {
            float translationY = (float) (heightAboveFeet - passengerAttachmentHeight(viewer));
            PacketEvents.getAPI().getPlayerManager().sendPacket(
                    viewer,
                    new WrapperPlayServerEntityMetadata(entityId, createDisplayBaseMetadata(translationY))
            );
        }

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

        List<EntityData<?>> metadata = usesDisplayEntities()
                ? createDisplayTextMetadata(text)
                : createArmorStandMetadata(text);

        PacketEvents.getAPI()
                .getPlayerManager()
                .sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityId, metadata));
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

    private double passengerAttachmentHeight(Player viewer) {
        ClientVersion clientVersion = PacketEvents.getAPI().getPlayerManager().getClientVersion(viewer);

        // 1.20.5+ attaches passengers at the top of the hitbox, older clients use height * 0.75.
        if (clientVersion != null && clientVersion.isNewerThanOrEquals(ClientVersion.V_1_20_5)) {
            return 1.8D;
        }

        return 1.35D;
    }

    private static int displayIndexShift() {
        // Display entity indices shifted by one in 1.20.2 (teleport duration was added).
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_20_2) ? 1 : 0;
    }

    private List<EntityData<?>> createDisplayBaseMetadata(float translationY) {
        int shift = displayIndexShift();

        List<EntityData<?>> metadata = new ArrayList<>(4);
        metadata.add(new EntityData<>(10 + shift, EntityDataTypes.VECTOR3F, new Vector3f(0.0F, translationY, 0.0F)));
        metadata.add(new EntityData<>(14 + shift, EntityDataTypes.BYTE, BILLBOARD_CENTER));
        // Transparent background, like an armor stand name tag.
        metadata.add(new EntityData<>(24 + shift, EntityDataTypes.INT, 0));
        metadata.add(new EntityData<>(26 + shift, EntityDataTypes.BYTE, TEXT_FLAG_SEE_THROUGH));
        return metadata;
    }

    private List<EntityData<?>> createDisplayTextMetadata(String text) {
        int shift = displayIndexShift();

        List<EntityData<?>> metadata = new ArrayList<>(1);
        metadata.add(new EntityData<>(22 + shift, EntityDataTypes.ADV_COMPONENT, toComponent(text)));
        return metadata;
    }

    private List<EntityData<?>> createArmorStandMetadata(String text) {
        List<EntityData<?>> metadata = new ArrayList<>(5);

        String jsonComponent = AdventureSerializer.getGsonSerializer().serialize(toComponent(text));

        metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20));
        metadata.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(jsonComponent)));
        metadata.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, true));
        metadata.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true));
        // 1.17 added "ticks frozen" to the shared entity metadata, pushing ArmorStand flags from 14 to 15.
        int armorStandFlagsIndex = VersionHelper.CURRENT_VERSION >= 1170 ? 15 : 14;
        metadata.add(new EntityData<>(armorStandFlagsIndex, EntityDataTypes.BYTE, (byte) 0x10));

        return metadata;
    }

    private Component toComponent(String text) {
        String coloredText = ChatColor.translateAlternateColorCodes('&', text);
        return LegacyComponentSerializer.legacySection().deserialize(coloredText);
    }
}
