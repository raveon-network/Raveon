package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.api.models.monitor.MonitorModelFilter;
import ru.raveon.api.models.monitor.ToggleResult;
import ru.raveon.config.MainConfigManager;
import ru.raveon.manager.analytic.MonitorManager;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@SubCommandRegister(permission = "raveon.command.monitor", aliases = "monitor")
public class MonitorSubCommand implements BuildableCommand {
    @Override
    public void handle(@NotNull CommandSender sender, @NotNull String[] args) {
        MainConfigManager config = Raveon.INSTANCE.getMainConfigManager();
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(config.getLanguageService().text("monitor.only_player"));
            return;
        }
        if (args.length < 2) {
            usage(viewer, config);
            return;
        }
        MonitorManager manager = Raveon.INSTANCE.getMonitorManager();
        try {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "stop" -> {
                    if (args.length != 2) {
                        usage(viewer, config);
                        return;
                    }
                    viewer.sendMessage(
                            manager.stop(viewer)
                                    ? config.getLanguageService().text("monitor.disabled")
                                    : config.getLanguageService().text("monitor.not_running"));
                }
                case "prob" -> prob(viewer, args, config, manager);
                case "chat" -> chat(viewer, args, config, manager);
                default -> usage(viewer, config);
            }
        } catch (IllegalArgumentException invalidModel) {
            usage(viewer, config);
        }
    }

    private void prob(
            Player viewer, String[] args, MainConfigManager config, MonitorManager manager) {
        if (args.length < 3 || args.length > 4 || args[2].isBlank()) {
            usage(viewer, config);
            return;
        }
        MonitorModelFilter filter =
                args.length == 4 ? MonitorModelFilter.parse(args[3]) : MonitorModelFilter.ALL;
        Player target = findPlayer(args[2]);
        if (target == null || !target.isOnline()) {
            viewer.sendMessage(config.getLanguageService().text("monitor.player_not_found"));
            return;
        }
        ToggleResult result = manager.toggle(viewer, target, filter);
        String response =
                switch (result) {
                    case ENABLED -> config.getLanguageService().text("monitor.enabled");
                    case SWITCHED -> config.getLanguageService().text("monitor.switched");
                    case DISABLED -> config.getLanguageService().text("monitor.disabled");
                };
        viewer.sendMessage(selection(response, target.getName(), filter));
    }

    private void chat(
            Player viewer, String[] args, MainConfigManager config, MonitorManager manager) {
        if (args.length > 4) {
            usage(viewer, config);
            return;
        }
        Player target = null;
        MonitorModelFilter filter = MonitorModelFilter.ALL;
        if (args.length == 3 && MonitorModelFilter.isArgument(args[2])) {
            filter = MonitorModelFilter.parse(args[2]);
        } else if (args.length >= 3) {
            if (args[2].isBlank()) {
                usage(viewer, config);
                return;
            }
            if (args.length == 4) filter = MonitorModelFilter.parse(args[3]);
            target = findPlayer(args[2]);
            if (target == null || !target.isOnline()) {
                viewer.sendMessage(config.getLanguageService().text("monitor.player_not_found"));
                return;
            }
        }
        ToggleResult result = manager.toggleChat(viewer, target, filter);
        String response =
                switch (result) {
                    case ENABLED -> config.getLanguageService().text("monitor.chat.enabled");
                    case SWITCHED -> config.getLanguageService().text("monitor.chat.switched");
                    case DISABLED -> config.getLanguageService().text("monitor.chat.disabled");
                };
        viewer.sendMessage(
                selection(
                        response,
                        target == null ? config.getLanguageService().text("monitor.chat.all_name") : target.getName(),
                        filter));
    }

    private static Player findPlayer(String name) {
        Player target = Bukkit.getPlayerExact(name);
        return target == null ? Bukkit.getPlayer(name) : target;
    }

    private static String selection(String template, String player, MonitorModelFilter filter) {
        return template.replace("{player}", player).replace("{model}", filter.displayName());
    }

    private static void usage(Player viewer, MainConfigManager config) {
        String message = config.getLanguageService().text("monitor.usage");
        viewer.sendMessage(message);
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 2)
            return Stream.of("chat", "prob", "stop")
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        if (args.length >= 3
                && (args[1].equalsIgnoreCase("chat") || args[1].equalsIgnoreCase("prob"))) {
            Stream<String> choices;
            if (args.length == 3) {
                choices = Bukkit.getOnlinePlayers().stream().map(HumanEntity::getName);
                if (args[1].equalsIgnoreCase("chat"))
                    choices = Stream.concat(choices, Stream.of("pro", "ultra", "all"));
            } else if (args.length == 4) choices = Stream.of("pro", "ultra", "all");
            else return Collections.emptyList();
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return choices.distinct()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }
}
