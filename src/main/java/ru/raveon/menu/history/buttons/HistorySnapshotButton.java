package ru.raveon.menu.history.buttons;

import org.bukkit.event.inventory.InventoryClickEvent;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.button.Button;
import ru.raveon.database.model.PlayerAIProbabilityData.ProbSnapshot;
import ru.raveon.menu.history.HistoryMenu;
import ru.raveon.menu.history.HistoryMenuPlaceholders;

public class HistorySnapshotButton extends Button {
    public HistorySnapshotButton(
            MenuItem menuItem,
            ProbSnapshot snapshot,
            int snapshotNumber,
            int slot,
            HistoryMenu menu
    ) {
        super(
                HistoryMenuPlaceholders.buildSnapshotItem(menuItem, snapshot, snapshotNumber, menu),
                slot
        );
    }

    @Override
    public void onClick(InventoryClickEvent event) {
    }
}
