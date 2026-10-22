package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.menu.players.PlayersMenu;

import java.util.List;

@SubCommandRegister(permission = "raveon.command.menu", aliases = "menu")
public class MenuSubCommand implements BuildableCommand {
    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if (!(commandSender instanceof Player player)) {
            return;
        }

        if (args.length < 2 || args[1].isBlank()) {
            new PlayersMenu().show(player);
            return;
        }

        HistorySubCommand.openHistoryMenu(player, args[1]);
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if (args.length == 2) {
            String input = args[1].toLowerCase();
            return org.bukkit.Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .toList();
        }

        return List.of();
    }
}
