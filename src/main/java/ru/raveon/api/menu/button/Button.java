package ru.raveon.api.menu.button;

import lombok.Getter;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import ru.raveon.api.itemstack.ItemBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Getter
public abstract class Button {
    private final List<Integer> slots;
    private final ItemStack itemStack;

    public Button(ItemStack itemStack, Integer... slots) {
        this.slots = Arrays.asList(slots);
        this.itemStack = new ItemBuilder(itemStack)
                .hideAttributes()
                .build();
    }

    public Button(ItemStack itemStack, List<Integer> slots) {
        this.slots = slots;
        this.itemStack = new ItemBuilder(itemStack)
                .hideAttributes()
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public abstract void onClick(InventoryClickEvent event);

    @FunctionalInterface
    public interface ButtonClickHandler {
        void onClick(InventoryClickEvent event);
    }

    public static class Builder {
        private final List<Integer> slots = new ArrayList<>();
        private ItemStack itemStack;

        public Builder itemStack(ItemStack itemStack) {
            this.itemStack = itemStack;
            return this;
        }

        public Builder slots(Integer... slots) {
            this.slots.addAll(Arrays.asList(slots));
            return this;
        }

        public Builder slots(List<Integer> slots) {
            this.slots.addAll(slots);
            return this;
        }

        public Button build(ButtonClickHandler handler) {
            return new Button(itemStack, slots) {
                @Override
                public void onClick(InventoryClickEvent event) {
                    handler.onClick(event);
                }
            };
        }
    }
}