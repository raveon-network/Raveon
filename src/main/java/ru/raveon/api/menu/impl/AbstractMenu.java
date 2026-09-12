package ru.raveon.api.menu.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.raveon.api.itemstack.ItemBuilder;
import ru.raveon.api.menu.IMenu;
import ru.raveon.api.menu.button.Button;
import ru.raveon.utils.StringColorize;

import java.util.*;
import java.util.logging.Level;

@Getter
@Setter
@NoArgsConstructor
public abstract class AbstractMenu implements InventoryHolder, IMenu {
    protected final List<Button> menuButtons = new ArrayList<>();
    protected final Map<Integer, ItemStack> menuItems = new HashMap<>();

    protected Inventory menuInventory;
    protected AbstractMenu parentMenu;

    protected Player opennerPlayer;

    public AbstractMenu(AbstractMenu parentMenu) {
        this.parentMenu = parentMenu;
    }

    @Override
    public void show(@NotNull Player player) {
        opennerPlayer = player;
        menuInventory = createInventory();

        populateInventory();
        player.openInventory(menuInventory);
        //Bukkit.getScheduler().runTaskLater(CoreService.coreInstance(), () -> player.openInventory(menuInventory), 1L);
    }

    protected Inventory createInventory() {
        //Component title = ComponentColorize.fromLegacyString(getTitle());
        String title = getTitle() != null ? StringColorize.parse(getTitle()) : "";
        return Bukkit.createInventory(this, getSize(), title);
    }

    protected void populateInventory() {
        menuInventory.clear();
        menuButtons.clear();
        menuItems.clear();

        getItemsAndButtons();
        setInventoryItems();
    }

    protected void setInventoryItems() {
        for (Button button : menuButtons) {
            for (int slot : button.getSlots()) {
                menuInventory.setItem(slot, button.getItemStack());
            }
        }

        for (var entry : menuItems.entrySet()) {
            if (menuInventory.getItem(entry.getKey()) != null) {
                continue;
            }

            menuInventory.setItem(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void onClick(@NotNull InventoryClickEvent event) {
        if (cancelDropClick() && event.getClick() == ClickType.DROP) {
            event.setCancelled(true);
            return;
        }

        for (Button button : menuButtons) {
            if (button.getSlots().contains(event.getSlot())) {
                button.onClick(event);
                break;
            }
        }

        event.setCancelled(true);
    }

    @Override
    public void onBottomInventoryClick(@NotNull InventoryClickEvent event) {
        // Пусто по умолчанию
    }

    @Override
    public void onClose(@NotNull InventoryCloseEvent event) {
        // Пусто по умолчанию
    }

    @Override
    public void onDrag(@NotNull InventoryDragEvent event) {
        event.setCancelled(true);
    }

    public void refreshMenu() {
        // Вызывается каждую секунду
    }

    public void removeButton(int slot) {
        menuInventory.clear(slot);
        menuButtons.removeIf(btn -> btn.getSlots().contains(slot));
    }

    public boolean isSlotOccupied(int slot) {
        return menuItems.containsKey(slot) || menuButtons.stream().anyMatch(button -> button.getSlots().contains(slot));
    }

    public void addDecorItems(@NotNull Material material, @NotNull List<Integer> slots) {
        ItemStack decor = new ItemBuilder(material)
                .flags(ItemFlag.HIDE_ATTRIBUTES)
                .build();

        for (int slot : slots) {
            menuItems.put(slot, decor);
        }
    }

    public void addDecorFromSection(@NotNull ConfigurationSection section) {
        section.getKeys(false).forEach(key -> {
            try {
                Material material = Material.valueOf(key.toUpperCase());
                addDecorItems(material, section.getIntegerList(key));
            } catch (IllegalArgumentException e) {
                Bukkit.getLogger().log(
                        Level.SEVERE,
                        "Ошибка при загрузке декорации для меню",
                        e
                );
            }
        });
    }

    public void addDecorItems(@NotNull Material material, @NotNull Integer... slots) {
        addDecorItems(material, Arrays.asList(slots));
    }

    public void addReturnButton(int slot, @NotNull ItemBuilder builder) {
        if (parentMenu == null) return;

        menuButtons.add(new Button(builder.build(), slot) {
            @Override
            public void onClick(@NotNull InventoryClickEvent event) {
                if (!(event.getWhoClicked() instanceof Player player)) {
                    return;
                }

                parentMenu.show(player);
            }
        });
    }

    @Nullable
    public Player getViewer() {
        List<Player> viewers = menuInventory.getViewers().stream()
                .filter(viewer -> viewer instanceof Player)
                .map(viewer -> (Player) viewer)
                .toList();
        return viewers.isEmpty() ? null : viewers.get(0);
    }

    protected boolean cancelDropClick() {
        return true;
    }

    public AbstractMenu getInstance() {
        return this;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return menuInventory;
    }
}
