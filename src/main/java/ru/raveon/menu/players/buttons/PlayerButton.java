package ru.raveon.menu.players.buttons;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.button.Button;
import ru.raveon.database.model.PlayerAIProbabilityData;
import ru.raveon.menu.history.HistoryMenu;
import ru.raveon.menu.players.PlayersMenu;
import ru.raveon.menu.players.PlayersMenuPlaceholders;
import ru.raveon.utils.ItemPlaceholderUtils;
import ru.raveon.utils.StringColorize;

import java.util.List;
import java.util.Map;

public class PlayerButton extends Button {
    private final MenuItem menuItem;
    private final PlayersMenu menu;

    private final Map<String, String> placeholders;
    private final PlayerAIProbabilityData playerData;

    public PlayerButton(
            MenuItem menuItem,
            PlayerAIProbabilityData playerData,
            int slot,
            PlayersMenu menu
    ) {
        super(PlayersMenuPlaceholders.buildPlayerItem(
                menuItem,
                playerData,
                menu.getPlayersMenuSettings(),
                menu.getPage(),
                menu.getMaxPages(),
                menu.getPlayersCount()
        ), slot);
        this.menuItem = menuItem;
        this.menu = menu;
        this.playerData = playerData;
        this.placeholders = PlayersMenuPlaceholders.playerPlaceholders(
                playerData,
                menu.getPlayersMenuSettings(),
                menu.getPage(),
                menu.getMaxPages(),
                menu.getPlayersCount()
        );
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        List<String> actions = getActions(event.getClick());
        for (String action : actions) {
            executeAction(player, action);
        }
    }

    private List<String> getActions(ClickType clickType) {
        return menuItem.getSection().getStringList("click_actions." + clickType.name().toLowerCase());
    }

    private void executeAction(Player player, String rawAction) {
        String action = ItemPlaceholderUtils.apply(rawAction, placeholders).trim();

        if (action.equalsIgnoreCase("[close]")) {
            player.closeInventory();
            return;
        }

        if (action.equalsIgnoreCase("[refresh]")) {
            menu.show(player);
            return;
        }

        if (action.equalsIgnoreCase("[open-history]")) {
            new HistoryMenu(playerData)
                    .show(player);
            return;
        }

        if (action.startsWith("[message]")) {
            player.sendMessage(StringColorize.parse(action.substring("[message]".length()).trim()));
            return;
        }
        if (action.startsWith("[console]")) {
            executeConsoleCommand(action.substring("[console]".length()).trim());
            return;
        }

        if (action.startsWith("[player]")) {
            executePlayerCommand(player, action.substring("[player]".length()).trim());
        }
    }

    private void executePlayerCommand(Player player, String command) {
        String preparedCommand = ItemPlaceholderUtils.apply(command, placeholders)
                .replaceFirst("^/", "")
                .trim();

        if (preparedCommand.isEmpty()) {
            return;
        }

        player.performCommand(preparedCommand);
    }

    private void executeConsoleCommand(String command) {
        String preparedCommand = ItemPlaceholderUtils.apply(command, placeholders)
                .replaceFirst("^/", "")
                .trim();

        if (preparedCommand.isEmpty()) {
            return;
        }

        CommandSender console = Bukkit.getConsoleSender();
        Bukkit.dispatchCommand(console, preparedCommand);
    }
}
