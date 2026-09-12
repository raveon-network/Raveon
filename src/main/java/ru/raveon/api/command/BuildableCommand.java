package ru.raveon.api.command;


import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface BuildableCommand {
    void handle(@NotNull CommandSender commandSender, @NotNull String[] args);

    List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args);
}
