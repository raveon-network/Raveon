package ru.raveon.listeners.packets;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import ru.raveon.Raveon;
import ru.raveon.manager.analytic.hologram.PacketHologramLine;

public final class HologramListener extends PacketListenerAbstract implements Listener {
    public HologramListener() {
        super(PacketListenerPriority.HIGHEST);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.isCancelled() || (event.getPacketType() != PacketType.Play.Client.PLAYER_POSITION
                && event.getPacketType() != PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) return;
        var manager = Raveon.INSTANCE.getHologramManager();
        Player player = event.getPlayer();
        if (manager == null || player == null || !manager.needsLegacyMovement(player.getUniqueId())) return;
        if (new WrapperPlayClientPlayerFlying(event).hasPositionChanged()) manager.handleMovement(player);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        var manager = Raveon.INSTANCE.getHologramManager();
        if (manager == null || event.isCancelled()) return;
        manager.observeScoreboard(event);
        Player viewer = event.getPlayer();
        if (viewer == null || !manager.hasHologramsEnabled(viewer.getUniqueId())) return;
        if (event.getPacketType() == PacketType.Play.Server.DESTROY_ENTITIES) {
            int[] ids = new WrapperPlayServerDestroyEntities(event).getEntityIds();
            event.getTasksAfterSend().add(() -> manager.handleEntitiesDestroyed(viewer, ids));
            return;
        }
        if (PacketHologramLine.usesDisplayEntities()
                && event.getPacketType() == PacketType.Play.Server.SET_PASSENGERS) {
            var packet = new WrapperPlayServerSetPassengers(event);
            int[] passengers = packet.getPassengers();
            int[] merged = manager.mergeOutgoingPassengers(
                    viewer.getUniqueId(), packet.getEntityId(), passengers);
            if (merged != passengers) {
                packet.setPassengers(merged);
                event.markForReEncode(true);
            }
            return;
        }
        int entityId = getSpawnedPlayerEntityId(event);
        if (entityId != -1) {
            event.getTasksAfterSend().add(() -> manager.handleTargetSpawned(viewer, entityId));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Raveon.INSTANCE.getHologramManager().handleLeave(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        var manager = Raveon.INSTANCE.getHologramManager();
        manager.removeViewer(event.getPlayer());
        manager.handleMovement(event.getPlayer());
    }

    @EventHandler
    public void onPlayerWorldChange(PlayerChangedWorldEvent event) {
        Raveon.INSTANCE.getHologramManager().handleWorldChange(event.getPlayer());
    }

    private int getSpawnedPlayerEntityId(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.SPAWN_PLAYER) {
            return new WrapperPlayServerSpawnPlayer(event).getEntityId();
        }
        if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            var packet = new WrapperPlayServerSpawnEntity(event);
            if (packet.getEntityType() == EntityTypes.PLAYER) return packet.getEntityId();
        }
        return -1;
    }
}
