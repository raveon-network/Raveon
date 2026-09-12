package ru.raveon.utils.entity;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;

public final class VanillaTargetEntityAdapter implements TargetEntityAdapter {
    public static final VanillaTargetEntityAdapter INSTANCE = new VanillaTargetEntityAdapter();

    private VanillaTargetEntityAdapter() {
    }

    @Override
    public @NotNull String getKey() {
        return "vanilla";
    }

    @Override
    public boolean shouldIndex(@NotNull Entity entity) {
        return entity.isValid() && entity.getType() != EntityType.UNKNOWN;
    }
}