package ru.raveon.menu.history;

import lombok.experimental.UtilityClass;
import org.bukkit.inventory.ItemStack;
import ru.raveon.Raveon;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.database.model.PlayerAIProbabilityData.Prob;
import ru.raveon.database.model.PlayerAIProbabilityData.ProbSnapshot;
import ru.raveon.utils.ItemPlaceholderUtils;
import ru.raveon.utils.TimeUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@UtilityClass
public class HistoryMenuPlaceholders {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .localizedBy(new Locale("ru"));

    public static ItemStack buildSnapshotItem(
            MenuItem menuItem,
            ProbSnapshot snapshot,
            int snapshotNumber,
            HistoryMenu menu
    ) {
        double average = getAverage(snapshot);
        ItemStack itemStack = ItemPlaceholderUtils.buildMenuItem(
                menuItem,
                snapshotPlaceholders(snapshot, snapshotNumber, menu)
        );
        itemStack.setType(menu.getHistoryMenuSettings().getChanceMaterial(average));
        return itemStack;
    }

    public static Map<String, String> menuPlaceholders(
            String playerName,
            int page,
            int maxPages,
            int snapshotsCount
    ) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", playerName);
        placeholders.put("player_name", playerName);
        placeholders.put("player-name", playerName);
        placeholders.put("page", String.valueOf(page + 1));
        placeholders.put("current_page", String.valueOf(page + 1));
        placeholders.put("max_page", String.valueOf(maxPages));
        placeholders.put("max_pages", String.valueOf(maxPages));
        placeholders.put("snapshots_count", String.valueOf(snapshotsCount));
        return placeholders;
    }

    public static Map<String, String> snapshotPlaceholders(
            ProbSnapshot snapshot,
            int snapshotNumber,
            HistoryMenu menu
    ) {
        Map<String, String> placeholders = menu.getMenuPlaceholders();
        List<Prob> entries = getEntries(snapshot);
        double average = getAverage(entries);
        double minimum = entries.stream().mapToDouble(Prob::getChance).min().orElse(0.0D);
        double maximum = entries.stream().mapToDouble(Prob::getChance).max().orElse(0.0D);
        long createdAt = getCreatedAt(snapshot, entries);
        long updatedAt = getUpdatedAt(createdAt, entries);

        placeholders.put("snapshot_number", String.valueOf(snapshotNumber));
        placeholders.put("snapshot_index", String.valueOf(snapshotNumber));
        placeholders.put("snapshot_count", String.valueOf(entries.size()));
        placeholders.put("snapshot_avg", formatChance(average));
        placeholders.put("snapshot_avg_percent", formatPercent(average));
        placeholders.put("snapshot_min", formatChance(minimum));
        placeholders.put("snapshot_max", formatChance(maximum));
        placeholders.put("snapshot_created_at", formatDateTime(createdAt));
        placeholders.put("snapshot_updated_at", formatDateTime(updatedAt));
        placeholders.put("snapshot_age", formatElapsed(createdAt));
        placeholders.put("snapshot_last_update", formatElapsed(updatedAt));
        placeholders.put("entries", formatEntries(entries, menu.getHistoryMenuSettings()));
        return placeholders;
    }

    public static double getAverage(ProbSnapshot snapshot) {
        return getAverage(getEntries(snapshot));
    }

    private static double getAverage(List<Prob> entries) {
        return entries.stream()
                .mapToDouble(Prob::getChance)
                .average()
                .orElse(0.0D);
    }

    private static List<Prob> getEntries(ProbSnapshot snapshot) {
        if (snapshot == null || snapshot.getProbabilities() == null) {
            return Collections.emptyList();
        }

        return snapshot.getProbabilities().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    private static String formatEntries(List<Prob> entries, HistoryMenuSettings settings) {
        if (entries.isEmpty()) {
            return settings.getEmptyEntriesMessage();
        }

        List<Prob> reversedEntries = new ArrayList<>(entries);
        Collections.reverse(reversedEntries);

        List<String> lines = new ArrayList<>(reversedEntries.size());
        for (Prob entry : reversedEntries) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("chance", formatChance(entry.getChance()));
            placeholders.put("chance_percent", formatPercent(entry.getChance()));
            placeholders.put("server", resolveServer(entry.getServerName(), settings));
            placeholders.put("time", formatElapsed(entry.getReceivedAt()));
            placeholders.put("received_at", formatDateTime(entry.getReceivedAt()));

            lines.add(ItemPlaceholderUtils.apply(settings.getEntryFormat(), placeholders));
        }

        return String.join("\n", lines);
    }

    private static String resolveServer(String serverName, HistoryMenuSettings settings) {
        if (serverName == null || serverName.isBlank()) {
            return settings.getEmptyServerValue();
        }
        return serverName;
    }

    private static long getCreatedAt(ProbSnapshot snapshot, List<Prob> entries) {
        if (snapshot != null && snapshot.getCreatedAt() > 0L) {
            return snapshot.getCreatedAt();
        }

        return entries.stream()
                .mapToLong(Prob::getReceivedAt)
                .filter(timestamp -> timestamp > 0L)
                .min()
                .orElse(0L);
    }

    private static long getUpdatedAt(long createdAt, List<Prob> entries) {
        return entries.stream()
                .mapToLong(Prob::getReceivedAt)
                .filter(timestamp -> timestamp > 0L)
                .max()
                .orElse(createdAt);
    }

    private static String formatChance(double probability) {
        return Raveon.INSTANCE.getMainConfigManager().getChanceString(probability);
    }

    private static String formatPercent(double probability) {
        return Raveon.INSTANCE.getMainConfigManager().getPercentString(probability);
    }

    private static String formatDateTime(long timestamp) {
        if (timestamp <= 0L) {
            return "—";
        }
        return DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    private static String formatElapsed(long timestamp) {
        if (timestamp <= 0L) {
            return "—";
        }

        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        int seconds = (int) Math.min(Integer.MAX_VALUE, elapsedSeconds);
        return TimeUtils.formatSeconds(seconds);
    }
}
