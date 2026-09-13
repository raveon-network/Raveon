package ru.raveon.api.menu.impl;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.raveon.Raveon;
import ru.raveon.utils.SchedulerUtils;

import java.util.concurrent.CompletableFuture;

public abstract class AsyncMenu extends AbstractMenu {
    public void show(@NotNull Player player) {
        opennerPlayer = player;
        menuInventory = createInventory();

        async(() -> {
            this.populateInventory();
            SchedulerUtils.runEntity(Raveon.INSTANCE, player, () -> player.openInventory(menuInventory));
        });
    }

    protected void populateInventory() {
        menuInventory.clear();
        menuButtons.clear();
        menuItems.clear();

        getItemsAndButtons();
        sync(this::setInventoryItems);
    }

    private void async(Runnable runnable) {
        CompletableFuture.runAsync(runnable);
    }

    private void sync(Runnable runnable) {
        SchedulerUtils.run(Raveon.INSTANCE, runnable);
    }
}
