package ru.raveon.integration.worldguard;

import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;
import ru.raveon.api.configuration.CustomConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Getter
public final class WorldGuardRegionBypassConfig {
    private final boolean enabled;
    private final Map<String, Set<String>> disabledRegionsByWorld;
    private final Map<String, Set<String>> enabledRegionsByWorld;

    private WorldGuardRegionBypassConfig(
            boolean enabled,
            Map<String, Set<String>> disabledRegionsByWorld,
            Map<String, Set<String>> enabledRegionsByWorld
    ) {
        this.enabled = enabled;
        this.disabledRegionsByWorld = disabledRegionsByWorld;
        this.enabledRegionsByWorld = enabledRegionsByWorld;
    }

    public static WorldGuardRegionBypassConfig fromConfig(CustomConfig config, String path) {
        boolean enabled = config.getBoolean(path + ".enabled", false);
        return new WorldGuardRegionBypassConfig(
                enabled,
                loadRegions(config, path + ".disabled-regions"),
                loadRegions(config, path + ".enabled-regions")
        );
    }

    public boolean isEmpty() {
        return disabledRegionsByWorld.isEmpty() && enabledRegionsByWorld.isEmpty();
    }

    private static Map<String, Set<String>> loadRegions(CustomConfig config, String path) {
        ConfigurationSection section = config.getConfig().getConfigurationSection(path);
        if (section == null) {
            return Collections.emptyMap();
        }

        Map<String, Set<String>> regionsByWorld = new HashMap<>();
        for (String worldName : section.getKeys(false)) {
            Set<String> regions = normalizeRegions(section.getStringList(worldName));
            if (!regions.isEmpty()) {
                regionsByWorld.put(normalize(worldName), Collections.unmodifiableSet(regions));
            }
        }

        return Collections.unmodifiableMap(regionsByWorld);
    }

    private static Set<String> normalizeRegions(Iterable<String> regionNames) {
        Set<String> regions = new HashSet<>();
        for (String regionName : regionNames) {
            String normalizedRegion = normalize(regionName);
            if (!normalizedRegion.isEmpty()) {
                regions.add(normalizedRegion);
            }
        }
        return regions;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
