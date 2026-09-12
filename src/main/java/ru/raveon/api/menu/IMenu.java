package ru.raveon.api.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface IMenu {
    void show(@NotNull Player player);

    void onClick(@NotNull InventoryClickEvent event);

    void onBottomInventoryClick(@NotNull InventoryClickEvent event);

    void onClose(@NotNull InventoryCloseEvent event);

    void onDrag(@NotNull InventoryDragEvent event);

    @Nullable String getTitle();

    int getSize();

    void getItemsAndButtons();
}
