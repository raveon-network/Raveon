package ru.raveon.listeners.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import ru.raveon.Raveon;
import ru.raveon.utils.SchedulerUtils;

public final class MitigationListener implements Listener {
    private final Raveon plugin;

    public MitigationListener(Raveon plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof Player)
                || (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)
                || !SchedulerUtils.isOwned(attacker)) return;
        var settings = plugin.getChecksConfigManager();
        if (!settings.getMitigationSettings().enabled()
                || !settings.isAnalysisEnabled()) return;
        var player = plugin.getPlayerDataManager().getPlayer(attacker.getUniqueId());
        if (player == null || settings.isAimAiBypassedInRegion(attacker)) return;
        double multiplier = player.getCheckManager().getDetectionState().damageMultiplier(settings);
        if (multiplier < 1 && Double.isFinite(event.getDamage()) && event.getDamage() > 0)
            event.setDamage(event.getDamage() * multiplier);
    }
}
