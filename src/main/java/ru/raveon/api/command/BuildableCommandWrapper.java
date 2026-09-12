package ru.raveon.api.command;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record BuildableCommandWrapper(BuildableCommand command, List<String> aliases, String permission) {
    public boolean hasPermission(@NotNull CommandSender sender) {
        if (permission == null || permission.isEmpty()) {
            return true;
        }

        return sender.hasPermission(permission);
    }
}
