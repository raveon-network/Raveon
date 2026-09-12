package ru.raveon.listeners.bukkit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import ru.raveon.api.menu.impl.AbstractMenu;

public class MenuListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent event) {

        Inventory clicked = event.getClickedInventory();
        Inventory top = event.getView().getTopInventory();

        if (!(top.getHolder() instanceof AbstractMenu menu) || clicked == null) return;

        if (clicked.equals(top)) menu.onClick(event);
        else menu.onBottomInventoryClick(event);

    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory.getHolder() == null) return;
        if (inventory.getHolder() instanceof AbstractMenu menu) menu.onClose(event);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory.getHolder() == null) return;
        if (inventory.getHolder() instanceof AbstractMenu menu) menu.onDrag(event);
    }
}
