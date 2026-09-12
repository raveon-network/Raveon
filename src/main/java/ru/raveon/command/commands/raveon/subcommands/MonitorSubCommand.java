package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.api.models.monitor.ToggleResult;
import ru.raveon.config.MainConfigManager;
import ru.raveon.manager.analytic.MonitorManager;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

@SubCommandRegister(permission = "raveon.command.monitor", aliases = "monitor")
public class MonitorSubCommand implements BuildableCommand {
    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        MainConfigManager config = Raveon.INSTANCE.getMainConfigManager();

        if (!(commandSender instanceof Player viewer)) {
            commandSender.sendMessage(config.getMonitorOnlyPlayerMessage());
            return;
        }

        MonitorManager monitorManager = Raveon.INSTANCE.getMonitorManager();

        if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
            String response = monitorManager.stop(viewer)
                    ? config.getMonitorDisabledMessage()
                    : config.getMonitorNotRunningMessage();
            viewer.sendMessage(response);
            return;
        }

        Player target = extractPlayer(args, viewer);

        if (target == null || !target.isOnline()) {
            viewer.sendMessage(config.getMonitorPlayerNotFoundMessage());
            return;
        }

        ToggleResult result = monitorManager.toggle(viewer, target);

        String response = switch (result) {
            case ENABLED -> config.getMonitorEnabledMessage();
            case SWITCHED -> config.getMonitorSwitchedMessage();
            case DISABLED -> config.getMonitorDisabledMessage();
        };

        viewer.sendMessage(response.replace("{player}", target.getName()));
    }

    private static @Nullable Player extractPlayer(@NotNull String @NonNull [] args, Player viewer) {
        if (args.length == 1 || args[1].isBlank()) {
            return viewer;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            target = Bukkit.getPlayer(args[1]);
        }

        return target;
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if (args.length == 2) {
            return Stream.concat(
                            Stream.of("stop"),
                            Bukkit.getOnlinePlayers()
                                    .stream()
                                    .map(HumanEntity::getName)
                    )
                    .filter(value -> value.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }

        return Collections.emptyList();
    }
}
