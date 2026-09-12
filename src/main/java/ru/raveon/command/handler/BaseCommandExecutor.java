package ru.raveon.command.handler;

import lombok.Getter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommandWrapper;
import ru.raveon.api.command.register.CommandRegister;

import java.util.Collections;
import java.util.List;

@Getter
public abstract class BaseCommandExecutor extends SmartCommandExecutor {
    private final String name;
    private final String permission;

    public BaseCommandExecutor() {
        CommandRegister register = getClass().getAnnotation(CommandRegister.class);
        if (register == null) {
            throw new IllegalStateException("Аннотация @CommandRegister отсутствует у " + getClass().getName());
        }

        this.name = register.name();
        this.permission = register.permission();

        Raveon.INSTANCE.registerCommand(name, this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (permission != null && !sender.hasPermission(permission)) {
            sendNoPermissionsMessage(sender);
            return true;
        }

        if (args.length == 0) {
            return true;
        }

        BuildableCommandWrapper subCommand = getCommandByLabel(args[0]);
        if (subCommand == null) {
            return true;
        }

        if (!sender.hasPermission(subCommand.permission())) {
            sendNoPermissionsMessage(sender);
            return true;
        }

        subCommand.command().handle(sender, args);
        return true;
    }

    private void sendNoPermissionsMessage(@NotNull CommandSender sender) {
        String noPermissionsMessage = getNoPermissionMessage();
        if (noPermissionsMessage == null || noPermissionsMessage.isEmpty()) {
            return;
        }

        sender.sendMessage(noPermissionsMessage);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return getFilteredSubCommandAliases(args[0], sender);
        }

        BuildableCommandWrapper subCommand = getCommandByLabel(args[0]);
        return subCommand == null || !sender.hasPermission(subCommand.permission())
                ? Collections.emptyList()
                : subCommand.command().tabComplete(sender, args);
    }

    public abstract String getNoPermissionMessage();

    public abstract void registerWrappers();

    public abstract String handleNoArguments(@NotNull CommandSender sender);
}
