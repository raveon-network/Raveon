package ru.raveon.command.commands.raveon;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import ru.raveon.api.command.register.CommandRegister;
import ru.raveon.command.commands.raveon.subcommands.*;
import ru.raveon.command.handler.BaseCommandExecutor;

@CommandRegister(name = "raveon", permission = "raveon.command.use")
public class RaveonCommand extends BaseCommandExecutor {
    @Override
    public String getNoPermissionMessage() {
        return "";
    }

    @Override
    public void registerWrappers() {
        addSubCommand(new AlertsSubCommand());
        addSubCommand(new VerboseSubCommand());
        addSubCommand(new HologramsSubCommand());
        addSubCommand(new HistorySubCommand());
        addSubCommand(new MenuSubCommand());
        addSubCommand(new ReloadSubCommand());
        addSubCommand(new MonitorSubCommand());
        addSubCommand(new DataCollectSubCommand());
    }

    @Override
    public String handleNoArguments(@NotNull CommandSender sender) {
        return "";
    }
}
