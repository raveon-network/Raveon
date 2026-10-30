package ru.raveon.menu.players;

import lombok.Getter;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import ru.raveon.Raveon;
import ru.raveon.model.ModelId;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.button.Button;
import ru.raveon.api.menu.impl.ActionMenu;
import ru.raveon.database.model.PlayerAIProbabilityData;
import ru.raveon.menu.players.buttons.NextPageButton;
import ru.raveon.menu.players.buttons.PlayerButton;
import ru.raveon.menu.players.buttons.PreviousPageButton;
import ru.raveon.utils.ItemPlaceholderUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Getter
public class PlayersMenu extends ActionMenu<List<PlayerAIProbabilityData>> {
    private final PlayersMenuSettings playersMenuSettings;

    private ModelId model;
    private boolean loaded;
    private int page;
    private int maxPages = 1;
    private int playersCount;
    private List<PlayerAIProbabilityData> loadedPlayers = List.of();

    public PlayersMenu(PlayersMenuSettings menuSettings) {
        super(menuSettings);
        this.playersMenuSettings = menuSettings;
        this.model = PlayersMenuPlaceholders.defaultModel();
    }

    public PlayersMenu() {
        this(Raveon.INSTANCE.getMainConfigManager().getMenuSettings());
    }

    @Override
    public @Nullable String getTitle() {
        return ItemPlaceholderUtils.apply(
                playersMenuSettings.getTitle(),
                getMenuPlaceholders());
    }

    @Override
    protected Button createButton(MenuItem menuItem) {
        return switch (menuItem.getActionName()) {
            case "previous_page", "previous-page", "prev_page", "prev-page" ->
                    new PreviousPageButton(menuItem, this);
            case "next_page", "next-page" -> new NextPageButton(menuItem, this);
            case "switch_model" -> Button.builder()
                    .itemStack(ItemPlaceholderUtils.buildMenuItem(menuItem, getMenuPlaceholders()))
                    .slots(menuItem.getSlot())
                    .build(event -> {
                        if (isLoading() || !(event.getWhoClicked() instanceof Player viewer)) return;
                        model = model == ModelId.PRO ? ModelId.ULTRA : ModelId.PRO;
                        page = 0;
                        loaded = false;
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
                                            loaded = false;
                                            show(player);
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
    protected CompletableFuture<List<PlayerAIProbabilityData>> loadData() {
        if (loaded) return CompletableFuture.completedFuture(loadedPlayers);
        ModelId selected = model;
        int limit = playersMenuSettings.getChecksLimit();
        return Raveon.INSTANCE.getPlayerOnlineService().getOnlinePlayerUuidsAsync()
                .thenCompose(values -> {
                    Set<UUID> ids = new HashSet<>();
                    for (String value : values) {
                        try {
                            ids.add(UUID.fromString(value));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    var storage = Raveon.INSTANCE.getViolationManager().getProbabilityStorage();
                    List<UUID> players = new ArrayList<>(ids);
                    CompletableFuture<List<PlayerAIProbabilityData>> loaded =
                            CompletableFuture.completedFuture(new ArrayList<>());
                    for (int start = 0; start < players.size(); start += 1000) {
                        List<UUID> batch = List.copyOf(
                                players.subList(start, Math.min(start + 1000, players.size())));
                        loaded = loaded.thenCompose(result -> storage.getPlayersData(batch, limit, selected)
                                .thenApply(part -> {
                                    result.addAll(part);
                                    return result;
                                }));
                    }
                    return loaded;
                })
                .thenApply(values -> values.stream()
                        .filter(Objects::nonNull)
                        .map(data -> new RankedPlayer(data,
                                PlayersMenuPlaceholders.averageProbability(data, limit)))
                        .sorted(Comparator.comparingDouble(RankedPlayer::average).reversed())
                        .map(RankedPlayer::data)
                        .toList());
    }

    @Override
    protected boolean applyData(List<PlayerAIProbabilityData> data, Player viewer) {
        loadedPlayers = data;
        loaded = true;
        playersCount = data.size();
        int size = Math.max(1, playersMenuSettings.getPlayerSlots().size());
        maxPages = Math.max(1, (playersCount + size - 1) / size);
        page = Math.max(0, Math.min(page, maxPages - 1));
        return true;
    }

    @Override
    protected void onLoad() {
        if (playersMenuSettings.getPlayerForm() == null
                || playersMenuSettings.getPlayerSlots().isEmpty()) return;
        int size = playersMenuSettings.getPlayerSlots().size();
        int from = page * size;
        int end = Math.min(from + size, loadedPlayers.size());
        for (int index = from; index < end; index++) {
            menuButtons.add(new PlayerButton(
                    playersMenuSettings.getPlayerForm(),
                    loadedPlayers.get(index),
                    playersMenuSettings.getPlayerSlots().get(index - from),
                    this));
        }
    }

    public Map<String, String> getMenuPlaceholders() {
        Map<String, String> values = PlayersMenuPlaceholders.menuPlaceholders(page, maxPages, playersCount);
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

    private record RankedPlayer(PlayerAIProbabilityData data, double average) {}
}
