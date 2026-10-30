package ru.raveon.api.itemstack;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class ItemStackServices {
    private ItemStackServices() {}

    public static ItemStack skullHead() {
        return new ItemStack(Material.PLAYER_HEAD);
    }
}
