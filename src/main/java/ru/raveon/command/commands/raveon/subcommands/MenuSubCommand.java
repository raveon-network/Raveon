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

        new PlayersMenu().show(player);
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        return List.of();
    }
}
