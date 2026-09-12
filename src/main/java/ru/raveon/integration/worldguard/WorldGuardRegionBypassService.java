package ru.raveon.integration.worldguard;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;

@RequiredArgsConstructor
public final class WorldGuardRegionBypassService {
    private final Plugin plugin;
    private final WorldGuardRegionBypassConfig config;

    private boolean missingWorldGuardWarningSent;

    public boolean isBypassed(Player player) {
        if (player == null || config == null || !config.isEnabled() || config.isEmpty()) {
            return false;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            warnMissingWorldGuard();
            return false;
        }

        Set<String> disabledRegions = WorldGuardRegionMatcher.getRegions(
                player,
                config.getDisabledRegionsByWorld()
        );
        if (disabledRegions.isEmpty()) {
            return false;
        }

        Set<String> enabledRegions = WorldGuardRegionMatcher.getRegions(
                player,
                config.getEnabledRegionsByWorld()
        );
        Set<String> activeRegions = new WorldGuardRegionQuery().getApplicableRegionIds(player);

        if (hasAny(activeRegions, enabledRegions)) {
            return false;
        }

        return disabledRegions.contains("+")
                || hasAny(activeRegions, disabledRegions);
    }

    private boolean hasAny(Set<String> activeRegions, Set<String> configuredRegions) {
        if (configuredRegions.isEmpty()) {
            return false;
        }
        if (configuredRegions.contains("+") && !activeRegions.isEmpty()) {
            return true;
        }

        for (String activeRegion : activeRegions) {
            if (configuredRegions.contains(activeRegion)) {
                return true;
            }
        }
        return false;
    }

    private void warnMissingWorldGuard() {
        if (missingWorldGuardWarningSent) {
            return;
        }

        missingWorldGuardWarningSent = true;
        plugin.getLogger().warning("WorldGuard region bypass is enabled, but WorldGuard is not installed or enabled.");
    }
}
