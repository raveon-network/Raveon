package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.SchedulerUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@SubCommandRegister(permission = "raveon.command.datacollect", aliases = "datacollect")
public final class DataCollectSubCommand implements BuildableCommand {
    private static final String START_PERMISSION = "raveon.command.datacollect.start";
    private static final String STOP_PERMISSION = "raveon.command.datacollect.stop";

    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if (args.length < 2) {
            commandSender.sendMessage(
                    Raveon.INSTANCE.getLanguageService().text("datacollect.start.help"));
            commandSender.sendMessage(
                    Raveon.INSTANCE.getLanguageService().text("datacollect.stop.help"));
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(commandSender, args);
            case "stop" -> handleStop(commandSender, args);
            default -> {
                commandSender.sendMessage(
                        Raveon.INSTANCE.getLanguageService().text("datacollect.start.help"));
                commandSender.sendMessage(
                        Raveon.INSTANCE.getLanguageService().text("datacollect.stop.help"));
            }
        }
    }

    private void handleStart(CommandSender commandSender, String[] args) {
        if (!commandSender.hasPermission(START_PERMISSION)) {
            return;
        }

        if (args.length < 4) {
            commandSender.sendMessage(
                    Raveon.INSTANCE.getLanguageService().text("datacollect.start.help"));
            return;
        }

        String targetName = args[2];
        String type = args[3].toLowerCase(Locale.ROOT);
        ModelId model;
        try {
            model = args.length > 5 ? ModelId.parse(args[5]) : ModelId.PRO;
        } catch (IllegalArgumentException ex) {
            commandSender.sendMessage(Raveon.INSTANCE.getLanguageService().text("datacollect.invalid_model"));
            return;
        }

        Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer == null) {
            commandSender.sendMessage(
                    Raveon.INSTANCE
                            .getLanguageService().text("datacollect.offline")
                            .replace("{target}", targetName));
            return;
        }

        RaveonPlayer raveonPlayer =
                Raveon.INSTANCE.getPlayerDataManager().getPlayer(targetPlayer.getUniqueId());
        if (raveonPlayer == null) {
            return;
        }

        if (raveonPlayer.getTrainData(model).isDatasetsCollecting()) {
            commandSender.sendMessage(
                    Raveon.INSTANCE
                            .getLanguageService().text("datacollect.already_collecting")
                            .replace("{target}", targetName));
            return;
        }

        if (!type.equals("cheat") && !type.equals("legit")) {
            commandSender.sendMessage(
                    Raveon.INSTANCE.getLanguageService().text("datacollect.invalid_type"));
            return;
        }

        if (args.length < 5) {
            commandSender.sendMessage(
                    Raveon.INSTANCE.getLanguageService().text("datacollect.start.none_selected"));
            return;
        }

        java.util.UUID ownerUuid = commandSender instanceof Player collector ? collector.getUniqueId() : null;
        SchedulerUtils.runEntity(Raveon.INSTANCE, targetPlayer, () -> {
            boolean started = raveonPlayer.startDatasetsCollection(args[4], type.equals("cheat"), model, ownerUuid);
            reply(commandSender, started
                    ? Raveon.INSTANCE.getLanguageService().text("datacollect.start.success").replace("{target}", raveonPlayer.getName())
                    : Raveon.INSTANCE.getLanguageService().text("datacollect.start.pending"));
        });
    }

    private void handleStop(CommandSender commandSender, String[] args) {
        if (!commandSender.hasPermission(STOP_PERMISSION)) {
            return;
        }

        if (args.length < 3) {
            commandSender.sendMessage(
                    Raveon.INSTANCE.getLanguageService().text("datacollect.stop.help"));
            return;
        }

        String targetName = args[2];
        ModelId model;
        try {
            model = args.length > 3 ? ModelId.parse(args[3]) : ModelId.PRO;
        } catch (IllegalArgumentException ex) {
            commandSender.sendMessage(Raveon.INSTANCE.getLanguageService().text("datacollect.invalid_model"));
            return;
        }

        Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer == null) {
            commandSender.sendMessage(
                    Raveon.INSTANCE
                            .getLanguageService().text("datacollect.offline")
                            .replace("{target}", targetName));
            return;
        }

        RaveonPlayer raveonPlayer =
                Raveon.INSTANCE.getPlayerDataManager().getPlayer(targetPlayer.getUniqueId());
        if (raveonPlayer == null) {
            return;
        }

        if (!raveonPlayer.getTrainData(model).isDatasetsCollecting()
                && !raveonPlayer.getTrainData(model).hasPendingFrames()) {
            commandSender.sendMessage(
                    Raveon.INSTANCE
                            .getLanguageService().text("datacollect.stop.no_collecting")
                            .replace("{target}", targetName));
            return;
        }

        SchedulerUtils.runEntity(Raveon.INSTANCE, targetPlayer, () ->
                raveonPlayer.getTrainData(model).stopCollecting().whenComplete((saved, error) ->
                        reply(commandSender, error == null && Boolean.TRUE.equals(saved)
                                ? Raveon.INSTANCE.getLanguageService().text("datacollect.stop.success").replace("{target}", raveonPlayer.getName())
                                : Raveon.INSTANCE.getLanguageService().text("datacollect.stop.failed"))));

    }

    private void reply(CommandSender sender, String message) {
        if (!Raveon.INSTANCE.isEnabled()) return;
        if (sender instanceof Player player)
            SchedulerUtils.runEntity(Raveon.INSTANCE, player, () -> player.sendMessage(message));
        else SchedulerUtils.run(Raveon.INSTANCE, () -> sender.sendMessage(message));
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if ((args.length == 6 && args[1].equalsIgnoreCase("start"))
                || (args.length == 4 && args[1].equalsIgnoreCase("stop")))
            return java.util.stream.Stream.of("pro", "ultra")
                    .filter(x -> x.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
                    .toList();
        if (args.length == 2) {
            return Stream.of("start", "stop")
                    .filter(command -> hasPermission(commandSender, command))
                    .filter(command -> command.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 3 && isStartOrStop(args[1])) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(HumanEntity::getName)
                    .filter(
                            player ->
                                    player.toLowerCase(Locale.ROOT)
                                            .startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 4 && args[1].equalsIgnoreCase("start")) {
            return Stream.of("legit", "cheat")
                    .filter(line -> line.startsWith(args[3].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 5
                && args[1].equalsIgnoreCase("start")
                && (args[3].equalsIgnoreCase("cheat") || args[3].equalsIgnoreCase("legit"))) {
            return Stream.of("name")
                    .filter(
                            line ->
                                    line.toLowerCase(Locale.ROOT)
                                            .startsWith(args[4].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return Collections.emptyList();
    }

    private boolean hasPermission(CommandSender commandSender, String command) {
        return switch (command) {
            case "start" -> commandSender.hasPermission(START_PERMISSION);
            case "stop" -> commandSender.hasPermission(STOP_PERMISSION);
            default -> false;
        };
    }

    private boolean isStartOrStop(String command) {
        return command.equalsIgnoreCase("start") || command.equalsIgnoreCase("stop");
    }
}
