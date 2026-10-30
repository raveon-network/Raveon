package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.menu.history.HistoryMenu;
import ru.raveon.utils.SchedulerUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@SubCommandRegister(permission = "raveon.command.history", aliases = "history")
public class HistorySubCommand implements BuildableCommand {
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    @Override
    public void handle(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) return;
        String targetName = args[1];
        if (args.length >= 3 && args[2].equalsIgnoreCase("menu")) {
            if (sender instanceof Player player) {
                openHistoryMenu(player, targetName);
            } else {
                sender.sendMessage(Raveon.INSTANCE.getMainConfigManager().getLanguageService().text("history-only-player"));
            }
            return;
        }
        String requestKey = sender instanceof Player player
                ? player.getUniqueId().toString() : sender.getName();
        if (!pending.add(requestKey)) return;
        var plugin = Raveon.INSTANCE;
        var storage = plugin.getViolationManager().getViolationStorage();
        var probabilityStorage = plugin.getViolationManager().getProbabilityStorage();
        var config = plugin.getMainConfigManager();
        int requestedPage = args.length >= 3 ? parsePage(args[2]) : 0;
        int limit = Math.max(1, Math.min(100, config.getHistoryEntryPerPage()));
        Player target = Bukkit.getPlayerExact(targetName);
        CompletableFuture<UUID> resolved = target == null
                ? probabilityStorage.resolvePlayerUuid(targetName)
                : CompletableFuture.completedFuture(target.getUniqueId());
        resolved.thenCompose(uuid -> {
            if (uuid == null) {
                return CompletableFuture.completedFuture(
                        List.of(config.getLanguageService().text("history-no-data").replace("{player}", targetName)));
            }
            return storage.getLogCount(uuid).thenCompose(count -> {
                int pages = Math.max(1, (int) Math.ceil((double) count / limit));
                int page = Math.min(requestedPage, pages - 1);
                return storage.getViolations(uuid, page, limit).thenApply(records -> {
                    List<String> messages = new ArrayList<>();
                    messages.add(config.getLanguageService().text("history-header")
                            .replace("{player}", targetName)
                            .replace("{page}", String.valueOf(page + 1))
                            .replace("{max_pages}", String.valueOf(pages)));
                    for (int i = records.size() - 1; i >= 0; i--) {
                        var record = records.get(i);
                        messages.add(config.getLanguageService().text("history-entry")
                                .replace("{check_name}", record.checkName())
                                .replace("{vl}", String.valueOf(record.vls()))
                                .replace("{verbose}", record.verbose())
                                .replace("{server}", record.server())
                                .replace("{time_ago}", formatTimeAgo(record.timestamp())));
                    }
                    return messages;
                });
            });
        }).whenComplete((messages, failure) -> {
            pending.remove(requestKey);
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, plugin.getLanguageService().plain("logs.history-load"), failure);
                sendMessages(sender, List.of(Raveon.INSTANCE.getLanguageService().text("errors.data-load")));
            } else {
                sendMessages(sender, messages);
            }
        });
    }

    private void sendMessages(CommandSender sender, List<String> messages) {
        Runnable send = () -> {
            if (sender instanceof Player player && !player.isOnline()) return;
            messages.forEach(sender::sendMessage);
        };
        if (sender instanceof Player player) {
            SchedulerUtils.runEntity(Raveon.INSTANCE, player, send);
        } else {
            SchedulerUtils.run(Raveon.INSTANCE, send);
        }
    }

    public static void openHistoryMenu(@NotNull Player player, @NotNull String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        new HistoryMenu(target == null ? null : target.getUniqueId(), targetName).show(player);
    }

    private int parsePage(String arg) {
        try {
            long page = Long.parseLong(arg);
            if (page <= 1L) return 0;
            return (int) Math.max(0L, Math.min(Integer.MAX_VALUE - 1L, page - 1L));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String formatTimeAgo(long timestamp) {
        return Raveon.INSTANCE.getLanguageService().duration(
                Math.max(0L, System.currentTimeMillis() - timestamp) / 1000L);
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return args.length == 3 ? List.of("menu") : Collections.emptyList();
    }
}
