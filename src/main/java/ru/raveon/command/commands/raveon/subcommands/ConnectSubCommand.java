package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.service.LanguageService;
import ru.raveon.service.analyze.AnalyzeConnection;
import ru.raveon.utils.SchedulerUtils;

import java.util.List;

@SubCommandRegister(permission = "raveon.command.connect", aliases = "connect")
public class ConnectSubCommand implements BuildableCommand {
    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        LanguageService language = Raveon.INSTANCE.getLanguageService();
        if (args.length != 2) {
            commandSender.sendMessage(language.text("connect.usage"));
            return;
        }

        commandSender.sendMessage(language.text("connect.checking"));
        Raveon.INSTANCE.getAnalyzeConnection().connect(args[1]).whenComplete((result, error) ->
                reply(commandSender, error == null ? message(language, result) : failure(language, error)));
    }

    private String message(LanguageService language, AnalyzeConnection.Result result) {
        return switch (result.status()) {
            case CONNECTED -> language.text("connect.success",
                    "server", result.server(), "models", result.modelNames());
            case REJECTED -> language.text("connect.rejected");
            case UNAVAILABLE -> language.text("connect.unavailable", "error", result.detail());
        };
    }

    private String failure(LanguageService language, Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = language.text("connect.failed", "error", String.valueOf(cause.getMessage()));
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
