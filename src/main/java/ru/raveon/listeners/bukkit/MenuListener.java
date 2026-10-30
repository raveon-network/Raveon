package ru.raveon.listeners.bukkit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.raveon.api.menu.impl.AbstractMenu;
import ru.raveon.api.menu.impl.AsyncMenu;

public class MenuListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        var top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof AbstractMenu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == top) menu.onClick(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        AsyncMenu.cancelPending(event.getPlayer().getUniqueId());
        if (event.getInventory().getHolder() instanceof AbstractMenu menu) menu.onClose(event);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AbstractMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        AsyncMenu.cancelPending(event.getPlayer().getUniqueId());
    }
}
