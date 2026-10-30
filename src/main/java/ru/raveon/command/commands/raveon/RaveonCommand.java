package ru.raveon.command.commands.raveon;

import ru.raveon.api.command.register.CommandRegister;
import ru.raveon.command.commands.raveon.subcommands.*;
import ru.raveon.command.handler.BaseCommandExecutor;

@CommandRegister(name = "raveonv2", permission = "raveon.command.use")
public class RaveonCommand extends BaseCommandExecutor {
    @Override
    public String getNoPermissionMessage() {
        return ru.raveon.Raveon.INSTANCE.getLanguageService().text("errors.no-permission");
    }

    @Override
    public void registerWrappers() {
        addSubCommand(new AlertsSubCommand());
        addSubCommand(new HologramsSubCommand());
        addSubCommand(new HistorySubCommand());
        addSubCommand(new MenuSubCommand());
        addSubCommand(new ReloadSubCommand());
        addSubCommand(new MonitorSubCommand());
        addSubCommand(new DataCollectSubCommand());
    }

}
