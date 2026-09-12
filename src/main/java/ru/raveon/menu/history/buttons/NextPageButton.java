package ru.raveon.menu.history.buttons;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.button.Button;
import ru.raveon.menu.history.HistoryMenu;
import ru.raveon.utils.ItemPlaceholderUtils;

public class NextPageButton extends Button {
    private final HistoryMenu menu;

    public NextPageButton(MenuItem menuItem, HistoryMenu menu) {
        super(ItemPlaceholderUtils.buildMenuItem(menuItem, menu.getMenuPlaceholders()), menuItem.getSlot());
        this.menu = menu;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!menu.hasNextPage()) {
            return;
        }

        menu.nextPage();
        menu.show(player);
    }
}
