package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;

import java.util.List;

@SubCommandRegister(permission = "raveon.command.holograms", aliases = {"hologram"})
public class HologramsSubCommand implements BuildableCommand {

    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if (!(commandSender instanceof Player player)) {
            return;
        }

        Raveon.INSTANCE.getHologramManager().toggleHolograms(player.getUniqueId(), false);
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        return List.of();
    }
}