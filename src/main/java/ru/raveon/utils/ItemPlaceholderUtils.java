package ru.raveon.utils;

import lombok.experimental.UtilityClass;
import org.bukkit.inventory.ItemStack;
import ru.raveon.api.itemstack.ItemBuilder;
import ru.raveon.api.menu.MenuItem;

import java.util.*;

@UtilityClass
public class ItemPlaceholderUtils {

    public List<String> applyLore(List<String> lore, Map<String, String> placeholders) {
        if (lore == null || lore.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (String line : lore) {
            String replaced = apply(line, placeholders);
            if (replaced.contains("\n")) {
                Collections.addAll(result, replaced.split("\n", -1));
                continue;
            }

            result.add(replaced);
        }

        return result;
    }

    public String apply(String text, Map<String, String> placeholders) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", Objects.toString(entry.getValue(), ""));
        }
        return result;
    }

    public ItemStack buildMenuItem(MenuItem menuItem, Map<String, String> placeholders) {
        ItemBuilder builder = menuItem.getItemBuilder();

        builder.name(ItemPlaceholderUtils.apply(builder.name(), placeholders))
                .lore(ItemPlaceholderUtils.applyLore(builder.lore(), placeholders));

        return builder.build();
    }
}
