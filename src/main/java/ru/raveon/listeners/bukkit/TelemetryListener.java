package ru.raveon.listeners.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import ru.raveon.Raveon;
import ru.raveon.checks.data.TelemetryUpdate;

public final class TelemetryListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        var player = Raveon.INSTANCE.getPlayerDataManager().getPlayer(event.getPlayer().getUniqueId());
        if (player == null) return;
        player.setBukkitPlayer(event.getPlayer());
        player.getCheckManager().resetForRespawn();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player bukkit)) return;
        var player = Raveon.INSTANCE.getPlayerDataManager().getPlayer(bukkit.getUniqueId());
        double damage = event.getFinalDamage();
        if (player != null && Double.isFinite(damage))
            player.getCheckManager().handleTelemetry(bukkit,
                    new TelemetryUpdate(TelemetryUpdate.Kind.DAMAGE, new double[] {damage}));
    }
}
