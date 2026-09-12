package ru.raveon.command.handler;

import lombok.AccessLevel;
import lombok.Getter;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.BuildableCommandWrapper;
import ru.raveon.api.command.register.SubCommandRegister;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public abstract class SmartCommandExecutor implements CommandExecutor, TabCompleter {
    @Getter(AccessLevel.PROTECTED)
    protected List<BuildableCommandWrapper> wrappers = new ArrayList<>();

    @Nullable
    protected BuildableCommandWrapper getCommandByLabel(@NotNull String label) {
        String lowerLabel = label.toLowerCase();
        return wrappers.stream()
                .filter(cmd -> cmd.aliases().stream()
                        .anyMatch(alias -> alias.equalsIgnoreCase(lowerLabel)))
                .findFirst()
                .orElse(null);
    }

    protected void addSubCommand(@NotNull BuildableCommand command, String permission, List<String> aliases) {
        wrappers.add(new BuildableCommandWrapper(command, aliases, permission));
    }

    protected void addSubCommand(@NotNull BuildableCommand command) {
        SubCommandRegister register = command.getClass().getAnnotation(SubCommandRegister.class);
        if (register == null) {
            throw new IllegalStateException("Аннотация @SubCommandRegister отсутствует у " + getClass().getName());
        }

        String permission = register.permission();
        List<String> aliases = Arrays.asList(register.aliases());
        addSubCommand(command, permission, aliases);
    }

    protected List<String> getAllSubCommandAliases(CommandSender sender) {
        return wrappers.stream()
                .filter(wrapper -> wrapper.hasPermission(sender))
                .flatMap(wrapper -> wrapper.aliases().stream())
                .collect(Collectors.toList());
    }

    protected List<String> getFilteredSubCommandAliases(@Nullable String input, CommandSender sender) {
        String filter = (input == null) ? "" : input.toLowerCase();
        return getAllSubCommandAliases(sender).stream()
                .filter(alias -> alias.toLowerCase().startsWith(filter))
                .collect(Collectors.toList());
    }
}
