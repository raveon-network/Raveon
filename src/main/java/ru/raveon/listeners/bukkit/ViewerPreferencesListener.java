package ru.raveon.listeners.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.raveon.Raveon;
import ru.raveon.utils.SchedulerUtils;

public final class ViewerPreferencesListener implements Listener {
    private final Raveon plugin;

    public ViewerPreferencesListener(Raveon plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        restore(event.getPlayer());
    }

    public void restore(Player player) {
        SchedulerUtils.runEntity(plugin, player, () -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            plugin.getAlertManager().restoreSession(player);
            plugin.getHologramManager().restoreSession(player);
            plugin.getMonitorManager().restoreOnJoin(player);
        });
    }
}
