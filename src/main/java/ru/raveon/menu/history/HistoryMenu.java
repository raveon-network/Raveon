package ru.raveon.menu.history;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import ru.raveon.Raveon;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.button.Button;
import ru.raveon.api.menu.impl.ActionMenu;
import ru.raveon.database.model.PlayerAIProbabilityData;
import ru.raveon.database.model.PlayerAIProbabilityData.ProbSnapshot;
import ru.raveon.menu.history.buttons.HistorySnapshotButton;
import ru.raveon.menu.history.buttons.NextPageButton;
import ru.raveon.menu.history.buttons.PreviousPageButton;
import ru.raveon.menu.players.PlayersMenuPlaceholders;
import ru.raveon.utils.ItemPlaceholderUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

@Getter
public class HistoryMenu extends ActionMenu {
    private final HistoryMenuSettings historyMenuSettings;
    private final UUID playerUuid;

    private String playerName;
    private PlayerAIProbabilityData playerData;

    private int page;
    private int maxPages = 1;
    private int snapshotsCount;

    public HistoryMenu(PlayerAIProbabilityData playerData) {
        this(Raveon.INSTANCE.getMainConfigManager().getHistorySettings(), playerData);
    }

    public HistoryMenu(HistoryMenuSettings menuSettings, PlayerAIProbabilityData playerData) {
        super(menuSettings);
        this.historyMenuSettings = menuSettings;
        this.playerData = playerData;

        this.playerUuid = extractPlayerUuid(playerData);
        this.playerName = extractPlayerName(playerData);
    }


    @Override
    public @Nullable String getTitle() {
        return ItemPlaceholderUtils.apply(
                historyMenuSettings.getTitle(),
                getMenuPlaceholders()
        );
    }

    @Override
    protected Button createButton(MenuItem menuItem) {
        return switch (menuItem.getActionName()) {
            case "previous_page", "previous-page", "prev_page", "prev-page" -> new PreviousPageButton(menuItem, this);
            case "next_page", "next-page" -> new NextPageButton(menuItem, this);
            case "refresh", "reload" -> Button.builder()
                    .itemStack(ItemPlaceholderUtils.buildMenuItem(menuItem, getMenuPlaceholders()))
                    .slots(menuItem.getSlot())
                    .build(event -> {
                        if (event.getWhoClicked() instanceof Player player) {
                            reloadPlayerData();
                            show(player);
                        }
                    });
            case "close" -> Button.builder()
                    .itemStack(ItemPlaceholderUtils.buildMenuItem(menuItem, getMenuPlaceholders()))
                    .slots(menuItem.getSlot())
                    .build(event -> event.getWhoClicked().closeInventory());
            default -> Button.builder()
                    .itemStack(ItemPlaceholderUtils.buildMenuItem(menuItem, getMenuPlaceholders()))
                    .slots(menuItem.getSlot())
                    .build(event -> {
                    });
        };
    }

    @Override
    protected void onLoad() {
        if (historyMenuSettings.getSnapshotForm() == null
                || historyMenuSettings.getSnapshotSlots().isEmpty()) {
            return;
        }

        PlayerAIProbabilityData loadedData = loadPlayerData();
        updatePlayerIdentity(loadedData);

        List<SnapshotView> snapshots = createSnapshotViews(loadedData);
        snapshotsCount = snapshots.size();
        maxPages = Math.max(
                1,
                (int) Math.ceil(snapshotsCount / (double) historyMenuSettings.getSnapshotSlots().size())
        );
        page = Math.max(0, Math.min(page, maxPages - 1));

        int fromIndex = page * historyMenuSettings.getSnapshotSlots().size();
        int toIndex = Math.min(
                fromIndex + historyMenuSettings.getSnapshotSlots().size(),
                snapshots.size()
        );

        if (fromIndex >= toIndex) {
            return;
        }

        List<SnapshotView> pageSnapshots = snapshots.subList(fromIndex, toIndex);
        for (int index = 0; index < pageSnapshots.size(); index++) {
            SnapshotView snapshotView = pageSnapshots.get(index);
            menuButtons.add(new HistorySnapshotButton(
                    historyMenuSettings.getSnapshotForm(),
                    snapshotView.snapshot(),
                    snapshotView.number(),
                    historyMenuSettings.getSnapshotSlots().get(index),
                    this
            ));
        }
    }

    public Map<String, String> getMenuPlaceholders() {
        return HistoryMenuPlaceholders.menuPlaceholders(
                playerName,
                page,
                maxPages,
                snapshotsCount
        );
    }

    public boolean hasPreviousPage() {
        return page > 0;
    }

    public boolean hasNextPage() {
        return page + 1 < maxPages;
    }

    public void previousPage() {
        if (hasPreviousPage()) {
            page--;
        }
    }

    public void nextPage() {
        if (hasNextPage()) {
            page++;
        }
    }

    public void reloadPlayerData() {
        playerData = null;
    }

    private PlayerAIProbabilityData loadPlayerData() {
        if (playerData != null) {
            return playerData;
        }

        try {
            if (playerUuid != null) {
                playerData = Raveon.INSTANCE
                        .getViolationManager()
                        .getProbabilityStorage()
                        .getPlayerDataByUUID(playerUuid)
                        .join();
            } else if (playerName != null && !playerName.isBlank()) {
                playerData = Raveon.INSTANCE
                        .getViolationManager()
                        .getProbabilityStorage()
                        .getPlayerDataByName(playerName)
                        .join();
            }
        } catch (CompletionException exception) {
            Bukkit.getLogger().log(
                    Level.SEVERE,
                    "Не удалось загрузить историю вероятностей игрока " + playerName,
                    exception
            );
        }

        return playerData;
    }

    private List<SnapshotView> createSnapshotViews(PlayerAIProbabilityData data) {
        if (data == null || data.getProbSnapshots() == null) {
            return new ArrayList<>();
        }

        List<SnapshotView> snapshots = new ArrayList<>();
        List<ProbSnapshot> source = data.getProbSnapshots();
        for (int index = 0; index < source.size(); index++) {
            ProbSnapshot snapshot = source.get(index);
            if (snapshot != null) {
                snapshots.add(new SnapshotView(index + 1, snapshot));
            }
        }

        snapshots.sort(Comparator
                .comparingLong((SnapshotView view) -> getSnapshotTimestamp(view.snapshot()))
                .thenComparingInt(SnapshotView::number)
                .reversed());
        return snapshots;
    }

    private long getSnapshotTimestamp(ProbSnapshot snapshot) {
        if (snapshot.getCreatedAt() > 0L) {
            return snapshot.getCreatedAt();
        }

        if (snapshot.getProbabilities() == null) {
            return 0L;
        }

        return snapshot.getProbabilities().stream()
                .filter(Objects::nonNull)
                .mapToLong(PlayerAIProbabilityData.Prob::getReceivedAt)
                .max()
                .orElse(0L);
    }

    private void updatePlayerIdentity(PlayerAIProbabilityData data) {
        if (data == null) {
            return;
        }

        String loadedPlayerName = extractPlayerName(data);
        if (!loadedPlayerName.equals("unknown")) {
            playerName = loadedPlayerName;
        }
    }

    private static UUID extractPlayerUuid(PlayerAIProbabilityData data) {
        return PlayersMenuPlaceholders.getPlayerUuid(data);
    }

    private static String extractPlayerName(PlayerAIProbabilityData data) {
        if (data == null || data.getPlayerData() == null) {
            return "unknown";
        }


        return normalizePlayerName(data.getPlayerData().getUsername());
    }

    private static String normalizePlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return "unknown";
        }

        return playerName;
    }

    private record SnapshotView(int number, ProbSnapshot snapshot) {
    }
}
