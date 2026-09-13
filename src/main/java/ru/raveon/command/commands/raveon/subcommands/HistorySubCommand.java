package ru.raveon.command.commands.raveon.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.raveon.Raveon;
import ru.raveon.api.command.BuildableCommand;
import ru.raveon.api.command.register.SubCommandRegister;
import ru.raveon.database.model.PlayerAIProbabilityData;
import ru.raveon.database.model.ViolationRecord;
import ru.raveon.menu.history.HistoryMenu;
import ru.raveon.utils.SchedulerUtils;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SubCommandRegister(permission = "raveon.command.history", aliases = "history")
public class HistorySubCommand implements BuildableCommand {

    @Override
    public void handle(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            return;
        }

        String targetName = args[1];

        if (isMenuRequested(args)) {
            openHistoryMenu(sender, targetName);
            return;
        }

        int page = getPage(args);

        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName).getBytes());

        var violationStorage = Raveon.INSTANCE.getViolationManager().getViolationStorage();
        var config = Raveon.INSTANCE.getMainConfigManager();

        int limit = config.getHistoryEntryPerPage();

        violationStorage.getLogCount(uuid).thenAccept(count -> {
            int maxPages = (int) Math.ceil((double) count / limit);

            violationStorage.getViolations(uuid, page, limit).thenAccept(records -> {

                String header = config.getHistoryHeaderMessage()
                        .replace("{player}", targetName)
                        .replace("{page}", String.valueOf(page + 1))
                        .replace("{max_pages}", String.valueOf(Math.max(maxPages, 1)));
                sendMessage(sender, header);

                for (int i = records.size() - 1; i >= 0; i--) {
                    ViolationRecord record = records.get(i);
                    String message = config.getHistoryEntryMessage()
                            .replace("{check_name}", record.checkName())
                            .replace("{vl}", String.valueOf(record.vls()))
                            .replace("{verbose}", record.verbose())
                            .replace("{server}", record.server())
                            .replace("{time_ago}", formatTimeAgo(record.timestamp()));
                    sendMessage(sender, message);
                }
            });
        });
    }

    private void sendMessage(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            SchedulerUtils.runEntity(Raveon.INSTANCE, player, () -> player.sendMessage(message));
        } else {
            SchedulerUtils.run(Raveon.INSTANCE, () -> sender.sendMessage(message));
        }
    }

    private boolean isMenuRequested(@NotNull String @NotNull [] args) {
        return args.length >= 3 && args[2].equalsIgnoreCase("menu");
    }

    private void openHistoryMenu(@NotNull CommandSender sender, @NotNull String targetName) {
        var config = Raveon.INSTANCE.getMainConfigManager();

        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getHistoryOnlyPlayerMessage());
            return;
        }

        var probabilityStorage = Raveon.INSTANCE.getViolationManager().getProbabilityStorage();
        Player target = Bukkit.getPlayer(targetName);

        CompletableFuture<PlayerAIProbabilityData> dataFuture = target != null
                ? probabilityStorage.getPlayerDataByUUID(target.getUniqueId())
                : probabilityStorage.getPlayerDataByName(targetName);

        dataFuture.whenComplete((playerData, throwable) -> SchedulerUtils.runEntity(
                Raveon.INSTANCE,
                player,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    if (throwable != null) {
                        Bukkit.getLogger().severe(String.format("Не удалось загрузить историю игрока %s", targetName));
                    }

                    if (playerData == null) {
                        player.sendMessage(
                                config.getHistoryNoDataMessage().replace("{player}", targetName)
                        );
                        return;
                    }

                    new HistoryMenu(playerData).show(player);
                }
        ));
    }

    private int getPage(@NotNull String @NotNull [] args) {
        return args.length >= 3 ? parsePage(args[2]) : 0;
    }

    private int parsePage(String arg) {
        try {
            int page = Integer.parseInt(arg);
            return Math.max(page - 1, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatTimeAgo(long timestamp) {
        long duration = System.currentTimeMillis() - timestamp;

        long days = TimeUnit.MILLISECONDS.toDays(duration);
        duration -= TimeUnit.DAYS.toMillis(days);

        long hours = TimeUnit.MILLISECONDS.toHours(duration);
        duration -= TimeUnit.HOURS.toMillis(hours);

        long minutes = TimeUnit.MILLISECONDS.toMinutes(duration);
        duration -= TimeUnit.MINUTES.toMillis(minutes);

        long seconds = TimeUnit.MILLISECONDS.toSeconds(duration);

        StringBuilder sb = new StringBuilder();

        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("s");

        return sb.toString().trim();
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 2) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 3) {
            return List.of("menu");
        }
        return Collections.emptyList();
    }
}
