package ru.raveon.utils.entity;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;

import lombok.RequiredArgsConstructor;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import ru.raveon.Raveon;
import ru.raveon.utils.SchedulerUtils;

@RequiredArgsConstructor
public final class TargetEntityIndexListener implements Listener {
    private final TargetEntityIndex targetEntityIndex;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdded(EntityAddToWorldEvent event) {
        targetEntityIndex.track(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveFromWorldEvent event) {
        targetEntityIndex.untrack(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        targetEntityIndex.track(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        SchedulerUtils.runEntity(Raveon.INSTANCE, event.getPlayer(),
                () -> targetEntityIndex.track(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        targetEntityIndex.untrack(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        targetEntityIndex.trackChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        targetEntityIndex.untrackChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        targetEntityIndex.trackWorld(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        targetEntityIndex.untrackWorld(event.getWorld().getUID());
    }
}
