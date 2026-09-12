package ru.raveon.menu.players.buttons;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.button.Button;
import ru.raveon.menu.players.PlayersMenu;
import ru.raveon.utils.ItemPlaceholderUtils;

public class PreviousPageButton extends Button {
    private final PlayersMenu menu;

    public PreviousPageButton(MenuItem menuItem, PlayersMenu menu) {
        super(ItemPlaceholderUtils.buildMenuItem(menuItem, menu.getMenuPlaceholders()), menuItem.getSlot());
        this.menu = menu;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!menu.hasPreviousPage()) {
            return;
        }

        menu.previousPage();
        menu.show(player);
    }
}
