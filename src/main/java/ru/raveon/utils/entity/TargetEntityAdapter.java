package ru.raveon.utils.entity;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

public interface TargetEntityAdapter {
    @NotNull String getKey();

    default int getPriority() {
        return 0;
    }

    boolean shouldIndex(@NotNull Entity entity);
}