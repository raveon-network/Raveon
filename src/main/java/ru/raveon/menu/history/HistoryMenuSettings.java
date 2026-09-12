package ru.raveon.menu.history;

import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.MenuSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

@Getter
public class HistoryMenuSettings extends MenuSettings {
    private List<Integer> snapshotSlots;

    private MenuItem snapshotForm;
    private String entryFormat;
    private String emptyEntriesMessage;
    private String emptyServerValue;

    private double yellowThreshold;
    private double orangeThreshold;
    private double redThreshold;

    private TreeMap<Double, Material> thresholds;

    public HistoryMenuSettings(@NotNull MenuSettings menuSettings) {
        super(
                menuSettings.getTitle(),
                menuSettings.getSize(),
                menuSettings.getDecoration(),
                menuSettings.getItems(),
                menuSettings.getSection()
        );

        ConfigurationSection settings = menuSettings.getSection().getConfigurationSection("settings");
        if (settings == null) {
            return;
        }

        List<Integer> configuredSlots = settings.getIntegerList("snapshot_slots");
        snapshotSlots = configuredSlots.isEmpty()
                ? createDefaultSnapshotSlots()
                : new ArrayList<>(configuredSlots);

        ConfigurationSection snapshotFormSection = settings.getConfigurationSection("snapshot_form");
        snapshotForm = snapshotFormSection == null ? null : MenuItem.fromSection(snapshotFormSection);

        entryFormat = settings.getString("entry_format", "{chance} &8| &f{server} &8| &f{time}");
        emptyEntriesMessage = settings.getString("empty_entries_message", "&7Нет записей");
        emptyServerValue = settings.getString("empty_server_value", "—");

        yellowThreshold = settings.getDouble("thresholds.yellow", 35.0D);
        orangeThreshold = settings.getDouble("thresholds.orange", 60.0D);
        redThreshold = settings.getDouble("thresholds.red", 80.0D);

        thresholds = new TreeMap<>();
        ConfigurationSection thresholdsSection = settings.getConfigurationSection("thresholds");

        if (thresholdsSection != null) {
            for (String key : thresholdsSection.getKeys(false)) {
                double chance = Double.parseDouble(key.replace(",", "."));
                thresholds.put(chance, readMaterial(thresholdsSection, key, Material.GRAY_STAINED_GLASS_PANE));
            }
        }
    }

    public Material getChanceMaterial(double averageProbability) {
        var entry = thresholds.floorEntry(averageProbability);
        return entry != null ? entry.getValue() : Material.GRAY_STAINED_GLASS_PANE;
    }

    public static HistoryMenuSettings fromSection(@NotNull ConfigurationSection section) {
        return new HistoryMenuSettings(MenuSettings.fromSection(section));
    }

    private double toPercentage(double probability) {
        double value = probability <= 1.0D ? probability * 100.0D : probability;
        return Math.max(0.0D, value);
    }

    private static Material readMaterial(ConfigurationSection settings, String path, Material defaultMaterial) {
        String materialName = settings.getString(path);
        if (materialName == null || materialName.isBlank()) {
            return defaultMaterial;
        }

        Material material = Material.matchMaterial(materialName);
        return material == null ? defaultMaterial : material;
    }

    private static List<Integer> createDefaultSnapshotSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            slots.add(slot);
        }

        return slots;
    }
}
