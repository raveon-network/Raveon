package ru.raveon.integration.worldguard;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class WorldGuardRegionMatcher {

    private WorldGuardRegionMatcher() {
    }

    static Set<String> getRegions(Player player, Map<String, Set<String>> regionsByWorld) {
        Set<String> regions = new HashSet<>(
                regionsByWorld.getOrDefault("+", Collections.emptySet())
        );
        regions.addAll(
                regionsByWorld.getOrDefault(
                        WorldGuardRegionBypassConfig.normalize(player.getWorld().getName()),
                        Collections.emptySet()
                )
        );
        return regions;
    }
}
