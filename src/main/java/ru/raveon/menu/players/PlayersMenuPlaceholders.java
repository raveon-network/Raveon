package ru.raveon.menu.players;

import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import ru.raveon.Raveon;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.database.model.PlayerAIProbabilityData;
import ru.raveon.utils.ItemPlaceholderUtils;
import ru.raveon.utils.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@UtilityClass
public class PlayersMenuPlaceholders {

    public static ItemStack buildPlayerItem(
            MenuItem menuItem,
            PlayerAIProbabilityData playerData,
            PlayersMenuSettings settings,
            int page,
            int maxPages,
            int playersCount
    ) {
        Map<String, String> placeholders = playerPlaceholders(
                playerData,
                settings,
                page,
                maxPages,
                playersCount
        );

        return ItemPlaceholderUtils.buildMenuItem(menuItem, placeholders);
    }

    public static Map<String, String> menuPlaceholders(int page, int maxPages, int playersCount) {
        Map<String, String> placeholders = new HashMap<>();

        placeholders.put("page", String.valueOf(page + 1));
        placeholders.put("current_page", String.valueOf(page + 1));
        placeholders.put("max_page", String.valueOf(maxPages));
        placeholders.put("max_pages", String.valueOf(maxPages));
        placeholders.put("players_count", String.valueOf(playersCount));

        return placeholders;
    }

    public static Map<String, String> playerPlaceholders(
            PlayerAIProbabilityData playerData,
            PlayersMenuSettings settings,
            int page,
            int maxPages,
            int playersCount
    ) {
        Map<String, String> placeholders = menuPlaceholders(page, maxPages, playersCount);

        String playerName = getPlayerName(playerData);
        UUID playerUuid = getPlayerUuid(playerData);
        List<Double> probabilities = safeProbabilities(playerData);
        List<Double> shownProbabilities = lastProbabilities(probabilities, settings.getChecksLimit());
        double lastChance = shownProbabilities.isEmpty()
                ? 0.0D
                : shownProbabilities.get(shownProbabilities.size() - 1);
        double avgChance = average(shownProbabilities);
        double maxChance = shownProbabilities.stream().mapToDouble(Double::doubleValue).max().orElse(0.0D);
        double minChance = shownProbabilities.stream().mapToDouble(Double::doubleValue).min().orElse(0.0D);

        placeholders.put("player", playerName);
        placeholders.put("player_name", playerName);
        placeholders.put("player-name", playerName);
        placeholders.put("player_uuid", playerUuid == null ? settings.getEmptyValue() : playerUuid.toString());
        placeholders.put("player-uuid", playerUuid == null ? settings.getEmptyValue() : playerUuid.toString());

        placeholders.put("last_chance", Raveon.INSTANCE.getMainConfigManager().getChanceString(lastChance));

        placeholders.put("avg", Raveon.INSTANCE.getMainConfigManager().getChanceString(avgChance));
        placeholders.put("avg_percent", Raveon.INSTANCE.getMainConfigManager().getPercentString(avgChance));
        placeholders.put("max_probability", Raveon.INSTANCE.getMainConfigManager().getChanceString(maxChance));
        placeholders.put("min_probability", Raveon.INSTANCE.getMainConfigManager().getChanceString(minChance));

        placeholders.put("probability_count", String.valueOf(probabilities.size()));
        placeholders.put("checks_count", String.valueOf(shownProbabilities.size()));

        placeholders.put("cheat_bar", formatCompactBar(avgChance, settings));
        placeholders.put("checks", String.join("\n", formatLastChecks(shownProbabilities, settings)));


        long lastCheckTime = System.currentTimeMillis() - Optional.ofNullable(playerData)
                .map(PlayerAIProbabilityData::getUpdatedAt)
                .orElse(System.currentTimeMillis());
        placeholders.put("last_check", TimeUtils.formatSeconds((int) lastCheckTime / 1000));

        placeholders.put("last_server", Optional.ofNullable(playerData)
                .map(PlayerAIProbabilityData::getLastServerName)
                .filter(server -> !server.isBlank())
                .orElse(settings.getEmptyValue()));

        return placeholders;
    }


    public static List<Double> safeProbabilities(PlayerAIProbabilityData playerData) {
        if (playerData == null || playerData.getProbabilities() == null) {
            return Collections.emptyList();
        }

        return playerData.getProbabilities();
    }

    public static List<Double> lastProbabilities(List<Double> probabilities, int limit) {
        if (probabilities == null || probabilities.isEmpty()) {
            return Collections.emptyList();
        }

        int from = limit <= 0 ? 0 : Math.max(0, probabilities.size() - limit);
        return new ArrayList<>(probabilities.subList(from, probabilities.size()));
    }

    public static double averageProbability(PlayerAIProbabilityData playerData, int limit) {
        return average(lastProbabilities(safeProbabilities(playerData), limit));
    }

    private static double average(List<Double> probabilities) {
        return probabilities.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0D);
    }

    public static String getPlayerName(PlayerAIProbabilityData playerData) {
        if (playerData == null || playerData.getPlayerData() == null) {
            return "unknown";
        }

        String playerName = playerData.getPlayerData().getUsername();
        if (playerName != null && !playerName.isBlank()) {
            return playerName;
        }

        UUID uniqueId = getPlayerUuid(playerData);
        if (uniqueId != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uniqueId);
            if (offlinePlayer.getName() != null) {
                return offlinePlayer.getName();
            }
        }

        return "unknown";
    }

    public static UUID getPlayerUuid(PlayerAIProbabilityData playerData) {
        if (playerData == null || playerData.getPlayerData() == null) {
            return null;
        }

        return playerData.getPlayerData().getUniqueId();
    }

    private static String formatCompactBar(double value, PlayersMenuSettings settings) {
        double clampedValue = Math.max(0.0D, Math.min(1.0D, value));
        int count = (int) (clampedValue * 10.0D);

        return Raveon.INSTANCE.getMainConfigManager().getChanceColor(clampedValue)
                + settings.getBarSymbol().repeat(count)
                + settings.getEmptyBarSymbol().repeat(10 - count);
    }

    private static List<String> formatLastChecks(List<Double> shownProbabilities, PlayersMenuSettings settings) {
        if (shownProbabilities == null || shownProbabilities.isEmpty()) {
            return List.of(settings.getNoChecksMessage());
        }

        List<Double> lastProbabilities = new ArrayList<>(shownProbabilities);
        Collections.reverse(lastProbabilities);

        List<String> lines = new ArrayList<>();
        for (int line = 0; line < settings.getCheckLines(); line++) {
            int start = line * settings.getProbabilitiesPerLine();
            if (start >= lastProbabilities.size()) {
                break;
            }

            int end = Math.min(start + settings.getProbabilitiesPerLine(), lastProbabilities.size());
            StringBuilder builder = new StringBuilder();
            for (int index = start; index < end; index++) {
                double probability = lastProbabilities.get(index);
                builder.append(" ");

                builder.append(Raveon.INSTANCE.getMainConfigManager().getChanceString(probability));
            }

            lines.add(builder.toString());
        }

        return lines.isEmpty() ? List.of(settings.getNoChecksMessage()) : lines;
    }
}
