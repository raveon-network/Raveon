package ru.raveon.api.menu;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.util.*;

@Slf4j
@Data
public class MenuSettings {
    private final String title;
    private final int size;

    private final Map<Material, List<Integer>> decoration;
    private final List<MenuItem> items;

    private final ConfigurationSection section;

    public static MenuSettings fromSection(@NotNull ConfigurationSection section) {
        final String title = section.getString("title", "");
        final int size = section.getInt("size", 9);

        final Map<Material, List<Integer>> decoration = Optional.ofNullable(section.getConfigurationSection("decoration"))
                .<Map<Material, List<Integer>>>map(decorSection -> decorSection.getKeys(false)
                        .stream()
                        .collect(HashMap::new,
                                (map, key) -> {
                                    Material material = Material.getMaterial(key);
                                    if (material != null) {
                                        map.put(material, decorSection.getIntegerList(key));
                                    }
                                },
                                Map::putAll))
                .orElse(new HashMap<>());

        final List<MenuItem> items = Optional.ofNullable(section.getConfigurationSection("items"))
                .<List<MenuItem>>map(decorSection -> decorSection.getKeys(false)
                        .stream()
                        .collect(ArrayList::new,
                                (list, key) -> {
                                    ConfigurationSection menuItemSection = decorSection.getConfigurationSection(key);
                                    if (menuItemSection != null) {
                                        list.add(MenuItem.fromSection(menuItemSection));
                                    }
                                },
                                List::addAll))
                .orElse(new ArrayList<>());

        return new MenuSettings(title, size, decoration, items, section);
    }

    public Map<Material, List<Integer>> getDecoration() {
        return Collections.unmodifiableMap(decoration);
    }

    public List<MenuItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
