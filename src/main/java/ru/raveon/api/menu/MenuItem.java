package ru.raveon.api.menu;

import lombok.Data;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import ru.raveon.api.itemstack.ItemBuilder;

@Data
public class MenuItem {
    private final ConfigurationSection section;
    private final ItemBuilder itemBuilder;

    private final @NotNull String action;
    private final int slot;

    public static MenuItem fromSection(@NotNull ConfigurationSection section) {
        final ItemBuilder itemBuilder = ItemBuilder.fromConfig(section);

        final String action = section.getString("action", "");
        final int slot = section.getInt("slot");

        return new MenuItem(section, itemBuilder, action, slot);
    }

    public ItemBuilder getItemBuilder() {
        return new ItemBuilder(itemBuilder.build());
    }

    public String getActionName() {
        return action.split(":")[0].toLowerCase();
    }

    public String getActionValue() {
        var action = this.action.split(":");
        return action.length > 1 ? action[1] : "";
    }
}
