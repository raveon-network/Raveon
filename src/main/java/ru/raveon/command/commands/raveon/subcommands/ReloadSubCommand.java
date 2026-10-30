package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.utils.SchedulerUtils;
import org.bukkit.entity.Player;

import java.util.List;

@SubCommandRegister(permission = "raveon.command.reload", aliases = "reload")
public class ReloadSubCommand implements BuildableCommand {
    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        commandSender.sendMessage(Raveon.INSTANCE.getMainConfigManager().getLanguageService().text("reloading"));

        SchedulerUtils.run(Raveon.INSTANCE, () -> {
            String result;
            try {
                Raveon.INSTANCE.reloadConfiguration();
                result = Raveon.INSTANCE.getMainConfigManager().getLanguageService().text("reloaded");
            } catch (RuntimeException error) {
                result = Raveon.INSTANCE.getLanguageService().text("reload-failed", "error", error.getMessage());
                Raveon.INSTANCE.getLogger().warning(org.bukkit.ChatColor.stripColor(result));
            }
            String message = result;
            if (commandSender instanceof Player player)
                SchedulerUtils.runEntity(Raveon.INSTANCE, player, () -> player.sendMessage(message));
            else commandSender.sendMessage(message);
        });
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        return List.of();
    }
}
