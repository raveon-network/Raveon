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

        if (args.length < 2) {
            viewer.sendMessage(config.getMonitorUsageMessage());
            return;
        }

        String mode = args[1].toLowerCase();

        switch (mode) {
            case "stop" -> {
                String response = monitorManager.stop(viewer)
                        ? config.getMonitorDisabledMessage()
                        : config.getMonitorNotRunningMessage();
                viewer.sendMessage(response);
            }
            case "prob" -> handleProb(viewer, args, config, monitorManager);
            case "chat" -> handleChat(viewer, args, config, monitorManager);
            default -> viewer.sendMessage(config.getMonitorUsageMessage());
        }
    }

    private void handleProb(Player viewer, String[] args, MainConfigManager config, MonitorManager monitorManager) {
        Player target = extractPlayer(args, 2, viewer);

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

    private void handleChat(Player viewer, String[] args, MainConfigManager config, MonitorManager monitorManager) {
        Player target = null;

        if (args.length > 2 && !args[2].isBlank()) {
            target = extractPlayer(args, 2, viewer);

            if (target == null || !target.isOnline()) {
                viewer.sendMessage(config.getMonitorPlayerNotFoundMessage());
                return;
            }
        }

        ToggleResult result = monitorManager.toggleChat(viewer, target);
        String name = target == null ? config.getMonitorChatAllName() : target.getName();

        String response = switch (result) {
            case ENABLED -> config.getMonitorChatEnabledMessage();
            case SWITCHED -> config.getMonitorChatSwitchedMessage();
            case DISABLED -> config.getMonitorChatDisabledMessage();
        };

        viewer.sendMessage(response.replace("{player}", name));
    }

    private static @Nullable Player extractPlayer(@NotNull String @NonNull [] args, int index, Player viewer) {
        if (args.length <= index || args[index].isBlank()) {
            return viewer;
        }

        Player target = Bukkit.getPlayerExact(args[index]);
        if (target == null) {
            target = Bukkit.getPlayer(args[index]);
        }

        return target;
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if (args.length == 2) {
            return Stream.of("chat", "prob", "stop")
                    .filter(value -> value.startsWith(args[1].toLowerCase()))
                    .toList();
        }

        if (args.length == 3 && (args[1].equalsIgnoreCase("chat") || args[1].equalsIgnoreCase("prob"))) {
            return Bukkit.getOnlinePlayers()
                    .stream()
                    .map(HumanEntity::getName)
                    .filter(value -> value.toLowerCase().startsWith(args[2].toLowerCase()))
                    .toList();
        }

        return Collections.emptyList();
    }
}
