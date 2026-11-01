package ru.raveon.manager.alert;

import lombok.NonNull;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import ru.raveon.config.MainConfigManager;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AlertManager {
    private final java.util.function.Supplier<MainConfigManager> configManager;
    private final Set<UUID> alertPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> alertCache = new ConcurrentHashMap<>();

    public AlertManager(java.util.function.Supplier<MainConfigManager> configManager) {
        this.configManager = configManager;
    }

    public boolean hasAlertsDisabled(@NonNull UUID uniqueId) {
        return Boolean.FALSE.equals(alertCache.get(uniqueId));
    }

    public boolean hasAlertsEnabled(@NonNull UUID uniqueId) {
        return alertPlayers.contains(uniqueId);
    }

    public void clearSession(@NonNull UUID uniqueId) {
        alertPlayers.remove(uniqueId);
    }

    public void restoreSession(Player player) {
        UUID id = player.getUniqueId();
        if (!player.hasPermission("raveon.command.alert")) {
            clearSession(id);
            return;
        }
        Boolean enabled = alertCache.get(id);
        if (enabled != null) setAlertsEnabled(id, enabled, true);
        else if (player.hasPermission("raveon.command.alert.enable-on-join"))
            setAlertsEnabled(id, true, true);
    }

    public boolean toggleAlerts(@NonNull UUID uniqueId, boolean silent) {
        boolean newState = !alertCache.getOrDefault(uniqueId, hasAlertsEnabled(uniqueId));
        setAlertsEnabled(uniqueId, newState, silent);
        return newState;
    }

    public void setAlertsEnabled(@NonNull UUID uniqueId, boolean enabled, boolean silent) {
        if (enabled) {
            alertPlayers.add(uniqueId);
        } else {
            alertPlayers.remove(uniqueId);
        }
        alertCache.put(uniqueId, enabled);

        if (!silent) {
            Player player = Bukkit.getPlayer(uniqueId);
            if (player != null && player.isOnline()) {
                ru.raveon.utils.SchedulerUtils.runEntity(ru.raveon.Raveon.INSTANCE, player, () -> player.sendMessage(
                        enabled
                                ? configManager.get().getLanguageService().text("alerts-enabled")
                                : configManager.get().getLanguageService().text("alerts-disabled")));
            }
        }
    }

    public void sendAlert(@NonNull String message) {
        sendAlert(message, Collections.emptySet());
    }

    public void sendAlert(@NonNull String message, Set<UUID> excludedPlayers) {
        alertPlayers.stream()
                .filter(uuid -> excludedPlayers == null || !excludedPlayers.contains(uuid))
                .map(Bukkit::getPlayer)
                .filter(player -> player != null && player.isOnline())
                .forEach(player -> ru.raveon.utils.SchedulerUtils.runEntity(ru.raveon.Raveon.INSTANCE, player, () -> player.sendMessage(message)));

        if (configManager.get().isPrintToConsole()) {
            Bukkit.getConsoleSender().sendMessage(message);
        }
    }
}
