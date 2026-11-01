package ru.raveon.menu.history;

import lombok.Getter;

import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import ru.raveon.Raveon;
import ru.raveon.model.ModelId;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.button.Button;
import ru.raveon.api.menu.impl.ActionMenu;
import ru.raveon.database.model.PlayerAIProbabilityData;
import ru.raveon.database.model.PlayerHistoryPage;
import ru.raveon.database.model.PlayerAIProbabilityData.ProbSnapshot;
import ru.raveon.menu.history.buttons.HistorySnapshotButton;
import ru.raveon.menu.history.buttons.NextPageButton;
import ru.raveon.menu.history.buttons.PreviousPageButton;
import ru.raveon.menu.players.PlayersMenuPlaceholders;
import ru.raveon.menu.players.PlayersMenu;
import ru.raveon.utils.SchedulerUtils;
import ru.raveon.utils.ItemPlaceholderUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Getter
public class HistoryMenu extends ActionMenu<PlayerHistoryPage> {
    private final HistoryMenuSettings historyMenuSettings;
    private final UUID playerUuid;

    private String playerName;
    private PlayerAIProbabilityData playerData;

    private ModelId model;
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
        this.model = playerData == null ? PlayersMenuPlaceholders.defaultModel() : playerData.getModel();

        this.playerUuid = extractPlayerUuid(playerData);
        this.playerName = extractPlayerName(playerData);
    }

    public HistoryMenu(UUID playerUuid, String playerName) {
        this(Raveon.INSTANCE.getMainConfigManager().getHistorySettings(), playerUuid, playerName);
    }

    private HistoryMenu(HistoryMenuSettings settings, UUID playerUuid, String playerName) {
        super(settings);
        this.historyMenuSettings = settings;
        this.playerUuid = playerUuid;
        this.playerName = normalizePlayerName(playerName);
        this.model = PlayersMenuPlaceholders.defaultModel();
    }

    @Override
    public @Nullable String getTitle() {
        return ItemPlaceholderUtils.apply(historyMenuSettings.getTitle(), getMenuPlaceholders());
    }

    @Override
    protected Button createButton(MenuItem menuItem) {
        return switch (menuItem.getActionName()) {
            case "previous_page", "previous-page", "prev_page", "prev-page" ->
                    new PreviousPageButton(menuItem, this);
            case "next_page", "next-page" -> new NextPageButton(menuItem, this);
            case "back", "back_to_players", "back-to-players" -> Button.builder()
                    .itemStack(ItemPlaceholderUtils.buildMenuItem(menuItem, getMenuPlaceholders()))
                    .slots(menuItem.getSlot())
                    .build(event -> {
                        if (isLoading() || !(event.getWhoClicked() instanceof Player viewer)) return;
                        SchedulerUtils.runEntity(Raveon.INSTANCE, viewer, () -> {
                            if (viewer.getOpenInventory().getTopInventory().getHolder() != this) return;
                            var destination = getParentMenu() != null ? getParentMenu()
                                    : new PlayersMenu();
                            destination.show(viewer);
                        });
                    });
            case "switch_model" -> Button.builder()
                    .itemStack(ItemPlaceholderUtils.buildMenuItem(menuItem, getMenuPlaceholders()))
                    .slots(menuItem.getSlot())
                    .build(event -> {
                        if (isLoading() || !(event.getWhoClicked() instanceof Player viewer)) return;
                        model = model == ModelId.PRO ? ModelId.ULTRA : ModelId.PRO;
                        page = 0;
                        show(viewer);
                    });
            case "refresh", "reload" ->
                    Button.builder()
                            .itemStack(
                                    ItemPlaceholderUtils.buildMenuItem(
                                            menuItem, getMenuPlaceholders()))
                            .slots(menuItem.getSlot())
                            .build(
                                    event -> {
                                        if (!isLoading() && event.getWhoClicked() instanceof Player player) {
                                            reloadPlayerData();
                                            show(player);
                                        }
                                    });
            case "close" ->
                    Button.builder()
                            .itemStack(
                                    ItemPlaceholderUtils.buildMenuItem(
                                            menuItem, getMenuPlaceholders()))
                            .slots(menuItem.getSlot())
                            .build(event -> {
                                if (event.getWhoClicked() instanceof Player viewer) {
                                    SchedulerUtils.runEntity(
                                            Raveon.INSTANCE, viewer, viewer::closeInventory);
                                }
                            });
            default ->
                    Button.builder()
                            .itemStack(
                                    ItemPlaceholderUtils.buildMenuItem(
                                            menuItem, getMenuPlaceholders()))
                            .slots(menuItem.getSlot())
                            .build(event -> {});
        };
    }

    @Override
    protected CompletableFuture<PlayerHistoryPage> loadData() {
        var storage = Raveon.INSTANCE.getViolationManager().getProbabilityStorage();
        int requestedPage = page;
        ModelId selected = model;
        int size = Math.max(1, historyMenuSettings.getSnapshotSlots().size());
        CompletableFuture<UUID> resolved = playerUuid == null
                ? storage.resolvePlayerUuid(playerName)
                : CompletableFuture.completedFuture(playerUuid);
        return resolved.thenCompose(id -> id == null
                ? CompletableFuture.completedFuture(null)
                : storage.getPlayerHistory(id, requestedPage, size, selected));
    }

    @Override
    protected boolean applyData(PlayerHistoryPage result, Player viewer) {
        if (result == null || result.data() == null) {
            viewer.sendMessage(Raveon.INSTANCE.getMainConfigManager().getLanguageService().text("history-no-data")
                    .replace("{player}", playerName));
            return false;
        }
        playerData = result.data();
        updatePlayerIdentity(playerData);
        page = result.page();
        snapshotsCount = result.totalSnapshots();
        int size = Math.max(1, historyMenuSettings.getSnapshotSlots().size());
        maxPages = (int) Math.max(1L, ((long) snapshotsCount + size - 1) / size);
        return true;
    }

    @Override
    protected void onLoad() {
        addBackButtonIfMissing();
        if (historyMenuSettings.getSnapshotForm() == null
                || historyMenuSettings.getSnapshotSlots().isEmpty()) return;
        List<ProbSnapshot> snapshots = playerData.getProbSnapshots();
        int size = historyMenuSettings.getSnapshotSlots().size();
        for (int index = 0; index < Math.min(snapshots.size(), size); index++) {
            menuButtons.add(new HistorySnapshotButton(
                    historyMenuSettings.getSnapshotForm(),
                    snapshots.get(index),
                    snapshotsCount - page * size - index,
                    historyMenuSettings.getSnapshotSlots().get(index),
                    this));
        }
    }

    private void addBackButtonIfMissing() {
        if (historyMenuSettings.getItems().stream().anyMatch(item ->
                List.of("back", "back_to_players", "back-to-players").contains(item.getActionName()))) return;
        var occupied = new java.util.HashSet<>(historyMenuSettings.getSnapshotSlots());
        historyMenuSettings.getItems().forEach(item -> occupied.add(item.getSlot()));
        int slot = getSize() - 6;
        if (occupied.contains(slot)) {
            slot = -1;
            for (int candidate = Math.max(0, getSize() - 9); candidate < getSize(); candidate++)
                if (!occupied.contains(candidate)) { slot = candidate; break; }
        }
        if (slot < 0) return;
        var item = new YamlConfiguration();
        item.set("material", "ARROW");
        item.set("action", "back");
        item.set("display_name", Raveon.INSTANCE.getLanguageService().text("menus.back"));
        item.set("slot", slot);
        menuButtons.add(createButton(MenuItem.fromSection(item)));
    }

    public Map<String, String> getMenuPlaceholders() {
        Map<String, String> values =
                HistoryMenuPlaceholders.menuPlaceholders(playerName, page, maxPages, snapshotsCount);
        values.put("model", model.displayName());
        return values;
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

}
