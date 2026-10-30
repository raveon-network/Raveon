package ru.raveon.api.menu.impl;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import ru.raveon.Raveon;
import ru.raveon.utils.SchedulerUtils;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public abstract class AsyncMenu<T> extends AbstractMenu {
    private static final ConcurrentHashMap<UUID, PendingOpen> pending = new ConcurrentHashMap<>();
    private volatile UUID loadingViewer;

    @Override
    public final void show(@NotNull Player player) {
        UUID id = player.getUniqueId();
        PendingOpen request = new PendingOpen(this);
        if (pending.putIfAbsent(id, request) != null) return;
        loadingViewer = id;
        try {
            if (!SchedulerUtils.runEntity(Raveon.INSTANCE, player,
                    () -> beginLoad(player, request), () -> pending.remove(id, request))) {
                pending.remove(id, request);
            }
        } catch (RuntimeException failure) {
            pending.remove(id, request);
            throw failure;
        }
    }

    private void beginLoad(Player player, PendingOpen request) {
        UUID id = player.getUniqueId();
        if (pending.get(id) != request || request.cancelled.get()
                || !player.isOnline() || !Raveon.INSTANCE.isEnabled()) {
            pending.remove(id, request);
            return;
        }
        request.previous = player.getOpenInventory().getTopInventory();
        opennerPlayer = player;
        CompletableFuture<T> future;
        try {
            future = loadData();
        } catch (RuntimeException error) {
            pending.remove(id, request);
            failed(player, error);
            return;
        }
        future.whenComplete((data, error) -> {
            try {
                if (!SchedulerUtils.runEntity(Raveon.INSTANCE, player, () -> {
                    if (!pending.remove(id, request)
                            || request.cancelled.get()
                            || !player.isOnline()
                            || !Raveon.INSTANCE.isEnabled()
                            || player.getOpenInventory().getTopInventory() != request.previous) return;
                    if (error != null) {
                        failed(player, error);
                        return;
                    }
                    try {
                        if (!applyData(data, player)) return;
                        menuInventory = createInventory();
                        populateInventory();
                        player.openInventory(menuInventory);
                    } catch (RuntimeException failure) {
                        failed(player, failure);
                    }
                }, () -> pending.remove(id, request))) pending.remove(id, request);
            } catch (RuntimeException failure) {
                pending.remove(id, request);
                if (Raveon.INSTANCE.isEnabled()) {
                    Raveon.INSTANCE.getLogger().log(Level.WARNING,
                            Raveon.INSTANCE.getLanguageService().plain("logs.menu-open"), failure);
                }
            }
        });
    }

    public final boolean isLoading() {
        UUID id = loadingViewer;
        if (id == null) return false;
        PendingOpen request = pending.get(id);
        return request != null && request.menu == this;
    }

    public static void cancelPending(UUID id) {
        PendingOpen request = pending.get(id);
        if (request != null) request.cancelled.set(true);
    }

    protected abstract CompletableFuture<T> loadData();

    protected abstract boolean applyData(T data, Player player);

    private void failed(Player player, Throwable error) {
        Raveon.INSTANCE.getLogger().log(Level.WARNING, Raveon.INSTANCE.getLanguageService().plain("logs.menu-load"), error);
        player.sendMessage(Raveon.INSTANCE.getLanguageService().text("errors.data-load"));
    }

    private static final class PendingOpen {
        private final AsyncMenu<?> menu;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Inventory previous;

        private PendingOpen(AsyncMenu<?> menu) {
            this.menu = menu;
        }
    }
}
