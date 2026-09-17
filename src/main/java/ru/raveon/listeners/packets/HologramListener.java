package ru.raveon.listeners.packets;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import ru.raveon.Raveon;
import ru.raveon.manager.analytic.hologram.HologramManager;
import ru.raveon.manager.analytic.hologram.PacketHologramLine;
import ru.raveon.utils.SchedulerUtils;

public final class HologramListener extends PacketListenerAbstract implements Listener {

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // Display holograms ride the player, only legacy armor stands need to follow movement.
        if (PacketHologramLine.usesDisplayEntities() || !isPositionPacket(event)) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }

        WrapperPlayClientPlayerFlying movementPacket = new WrapperPlayClientPlayerFlying(event);
        if (!movementPacket.hasPositionChanged()) {
            return;
        }

        runSync(player, () -> {
            if (player.isOnline()) {
                Raveon.INSTANCE.getHologramManager().handleMovement(player);
            }
        });
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        HologramManager hologramManager = Raveon.INSTANCE.getHologramManager();
        if (hologramManager == null) {
            return;
        }

        Player viewer = event.getPlayer();
        if (viewer == null) {
            return;
        }

        // Applies to both implementations: armor stands would linger, mounted lines would float in place.
        if (event.getPacketType() == PacketType.Play.Server.DESTROY_ENTITIES) {
            int[] entityIds = new WrapperPlayServerDestroyEntities(event).getEntityIds();
            hologramManager.handleEntitiesDestroyed(viewer, entityIds);
            return;
        }

        if (!PacketHologramLine.usesDisplayEntities()) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
            int[] passengers = packet.getPassengers();
            int[] merged = hologramManager.mergeOutgoingPassengers(viewer.getUniqueId(), packet.getEntityId(), passengers);

            if (merged != passengers) {
                packet.setPassengers(merged);
                event.markForReEncode(true);
            }
            return;
        }

        int spawnedEntityId = getSpawnedPlayerEntityId(event);
        if (spawnedEntityId != -1) {
            event.getTasksAfterSend().add(() -> hologramManager.handleTargetSpawned(viewer, spawnedEntityId));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        handleLeave(event.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        handleLeave(event.getPlayer());
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (PacketHologramLine.usesDisplayEntities()) {
            return;
        }

        Player player = event.getPlayer();

        Raveon.INSTANCE.getHologramManager().removeViewer(player);
        Raveon.INSTANCE.getHologramManager().handleMovement(player);
    }

    @EventHandler
    public void onPlayerWorldChange(PlayerChangedWorldEvent event) {
        Raveon.INSTANCE.getHologramManager().handleWorldChange(event.getPlayer());
    }

    private void handleLeave(Player player) {
        Raveon.INSTANCE.getHologramManager().handleLeave(player);
    }

    private int getSpawnedPlayerEntityId(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.SPAWN_PLAYER) {
            return new WrapperPlayServerSpawnPlayer(event).getEntityId();
        }

        if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
            if (packet.getEntityType() == EntityTypes.PLAYER) {
                return packet.getEntityId();
            }
        }

        return -1;
    }

    private boolean isPositionPacket(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    private void runSync(Player player, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }

        SchedulerUtils.runEntity(Raveon.INSTANCE, player, task);
    }
}
