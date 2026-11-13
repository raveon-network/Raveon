package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.config.MainConfigManager;
import ru.raveon.service.analyze.AnalyzeConnection;
import ru.raveon.utils.SchedulerUtils;

import java.util.List;

@SubCommandRegister(permission = "raveon.command.connect", aliases = "connect")
public class ConnectSubCommand implements BuildableCommand {
    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        MainConfigManager messages = Raveon.INSTANCE.getMainConfigManager();
        if (args.length != 2) {
            commandSender.sendMessage(messages.message("connect.usage"));
            return;
        }

        commandSender.sendMessage(messages.message("connect.checking"));
        Raveon.INSTANCE.getAnalyzeConnection().connect(args[1]).whenComplete((result, error) ->
                reply(commandSender, error == null ? message(messages, result) : failure(messages, error)));
    }

    private String message(MainConfigManager messages, AnalyzeConnection.Result result) {
        return switch (result.status()) {
            case CONNECTED -> messages.message("connect.success",
                    "server", result.server(), "models", result.modelNames());
            case REJECTED -> messages.message("connect.rejected");
            case UNAVAILABLE -> messages.message("connect.unavailable", "error", result.detail());
        };
    }

    private String failure(MainConfigManager messages, Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = messages.message("connect.failed", "error", String.valueOf(cause.getMessage()));
        Raveon.INSTANCE.getLogger().warning(org.bukkit.ChatColor.stripColor(message));
        return message;
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player)
            SchedulerUtils.runEntity(Raveon.INSTANCE, player, () -> player.sendMessage(message));
        else SchedulerUtils.run(Raveon.INSTANCE, () -> sender.sendMessage(message));
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        return List.of();
    }
}
