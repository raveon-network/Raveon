package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.player.RaveonPlayer;

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
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageStartHelp());
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageStopHelp());
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(commandSender, args);
            case "stop" -> handleStop(commandSender, args);
            default -> {
                commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageStartHelp());
                commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageStopHelp());
            }
        }
    }

    private void handleStart(CommandSender commandSender, String[] args) {
        if (!commandSender.hasPermission(START_PERMISSION)) {
            return;
        }

        if (args.length < 4) {
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageStartHelp());
            return;
        }

        String targetName = args[2];
        String type = args[3].toLowerCase(Locale.ROOT);

        Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer == null) {
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageAllOffline().replace("{target}", targetName));
            return;
        }

        RaveonPlayer raveonPlayer = Raveon.INSTANCE.getPlayerDataManager().getPlayer(targetPlayer.getUniqueId());
        if (raveonPlayer == null) {
            return;
        }

        if (raveonPlayer.getTrainData().isDatasetsCollecting()) {
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageAllAlreadyCollecting().replace("{target}", targetName));
            return;
        }

        if (!type.equals("cheat") && !type.equals("legit")) {
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageAllNoneType());
            return;
        }

        if (args.length < 5) {
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageStartNoneSelected());
            return;
        }

        setDatasetOwner(commandSender, raveonPlayer);
        raveonPlayer.startDatasetsCollection(args[4], type.equals("cheat"));

        commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageAllStart().replace("{target}", raveonPlayer.getName()));
    }

    private void handleStop(CommandSender commandSender, String[] args) {
        if (!commandSender.hasPermission(STOP_PERMISSION)) {
            return;
        }

        if (args.length < 3) {
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageStopHelp());
            return;
        }

        String targetName = args[2];

        Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer == null) {
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageAllOffline().replace("{target}", targetName));
            return;
        }

        RaveonPlayer raveonPlayer = Raveon.INSTANCE.getPlayerDataManager().getPlayer(targetPlayer.getUniqueId());
        if (raveonPlayer == null) {
            return;
        }

        if (!raveonPlayer.getTrainData().isDatasetsCollecting()) {
            commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageStopNoneCollecting().replace("{target}", targetName));
            return;
        }

        raveonPlayer.stopDatasetsCollection();
        commandSender.sendMessage(Raveon.INSTANCE.getDataCollectConfigManager().getMessageAllStop().replace("{target}", raveonPlayer.getName()));
    }

    private void setDatasetOwner(CommandSender commandSender, RaveonPlayer raveonPlayer) {
        if (commandSender instanceof Player player) {
            raveonPlayer.getTrainData().setDatasetsOwner(player.getUniqueId());
        }
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if (args.length == 2) {
            return Stream.of("start", "stop")
                    .filter(command -> hasPermission(commandSender, command))
                    .filter(command -> command.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 3 && isStartOrStop(args[1])) {
            return Bukkit.getOnlinePlayers()
                    .stream()
                    .map(HumanEntity::getName)
                    .filter(player -> player.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 4 && args[1].equalsIgnoreCase("start")) {
            return Stream.of("legit", "cheat")
                    .filter(line -> line.startsWith(args[3].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 5 && args[1].equalsIgnoreCase("start")
                && (args[3].equalsIgnoreCase("cheat") || args[3].equalsIgnoreCase("legit"))) {
            return Stream.of("name")
                    .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(args[4].toLowerCase(Locale.ROOT)))
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
