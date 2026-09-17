package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.player.RaveonPlayer;

import java.util.List;

@SubCommandRegister(permission = "raveon.command.reload", aliases = "reload")
public class ReloadSubCommand implements BuildableCommand {
    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        commandSender.sendMessage(Raveon.INSTANCE.getMainConfigManager().getReloadingMessage());

        Raveon.INSTANCE.getMainConfigManager().reloadAll();
        Raveon.INSTANCE.getChecksConfigManager().reloadAll();
        Raveon.INSTANCE.getHologramConfigManager().reloadAll();
        Raveon.INSTANCE.getDataCollectConfigManager().reloadAll();
        Raveon.INSTANCE.getPunishmentConfigManager().reloadAll();
        Raveon.INSTANCE.getPlayerDataManager().getEntries().forEach(RaveonPlayer::reload);
        // Holograms are respawned so reloaded offset/spacing apply to lines that are already shown.
        Raveon.INSTANCE.getHologramManager().handleConfigReload();

        commandSender.sendMessage(Raveon.INSTANCE.getMainConfigManager().getReloadedMessage());
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        return List.of();
    }
}
