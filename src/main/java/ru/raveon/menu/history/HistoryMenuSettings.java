package ru.raveon.menu.history;

import lombok.Getter;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.MenuSettings;
import ru.raveon.service.LanguageService;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

@Getter
public class HistoryMenuSettings extends MenuSettings {
    private List<Integer> snapshotSlots = List.of();

    private MenuItem snapshotForm;
    private String entryFormat;
    private String emptyEntriesMessage;
    private String emptyServerValue;


    private TreeMap<Double, Material> thresholds = new TreeMap<>();

    public HistoryMenuSettings(@NotNull MenuSettings menuSettings, LanguageService language) {
        super(
                menuSettings.getTitle(),
                menuSettings.getSize(),
                menuSettings.getDecoration(),
                menuSettings.getItems(),
                menuSettings.getSection());

        entryFormat = language.text("menus.history.entry");
        emptyEntriesMessage = language.text("menus.history.empty_entries");
        emptyServerValue = language.text("menus.history.empty_server");

        ConfigurationSection settings =
                menuSettings.getSection().getConfigurationSection("settings");
        if (settings == null) {
            return;
        }

        List<Integer> configuredSlots = settings.getIntegerList("snapshot_slots");
        snapshotSlots =
                configuredSlots.isEmpty()
                        ? createDefaultSnapshotSlots()
                        : MenuSettings.validateSlots(configuredSlots, getSize());

        ConfigurationSection snapshotFormSection =
                settings.getConfigurationSection("snapshot_form");
        snapshotForm =
                snapshotFormSection == null ? null : MenuItem.fromSection(snapshotFormSection);

        entryFormat = settings.getString("entry_format", "{chance} &8| &f{server} &8| &f{time}");
        emptyEntriesMessage = settings.getString("empty_entries_message", emptyEntriesMessage);
        emptyServerValue = settings.getString("empty_server_value", "—");


        MenuSettings.validateSlots(snapshotSlots, getSize());
        ConfigurationSection thresholdsSection = settings.getConfigurationSection("thresholds");

        if (thresholdsSection != null) {
            for (String key : thresholdsSection.getKeys(false)) {
                double chance = Double.parseDouble(key.replace(",", "."));
                if (!Double.isFinite(chance) || chance < 0 || chance > 1) {
                    throw new IllegalArgumentException("History threshold must be between 0 and 1");
                }
                thresholds.put(
                        chance,
                        readMaterial(thresholdsSection, key, Material.GRAY_STAINED_GLASS_PANE));
            }
        }
    }

    public Material getChanceMaterial(double averageProbability) {
        var entry = thresholds.floorEntry(averageProbability);
        return entry != null ? entry.getValue() : Material.GRAY_STAINED_GLASS_PANE;
    }

    public static HistoryMenuSettings fromSection(@NotNull ConfigurationSection section, LanguageService language) {
        return new HistoryMenuSettings(MenuSettings.fromSection(language.localize(section)), language);
    }

    private static Material readMaterial(
            ConfigurationSection settings, String path, Material defaultMaterial) {
        String materialName = settings.getString(path);
        if (materialName == null || materialName.isBlank()) {
            return defaultMaterial;
        }

        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException("Invalid history material: " + materialName);
        }
        return material;
    }

    private List<Integer> createDefaultSnapshotSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < Math.min(36, getSize()); slot++) {
            slots.add(slot);
        }

        return slots;
    }
}
