package ru.raveon.api.events;

import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import ru.raveon.api.models.data.TrainData;

@Getter
public final class StartTrainEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player target;
    private final TrainData trainData;
    private final Player owner;
    private final boolean markedCheater;
    private final String datasetName;

    public StartTrainEvent(
            @NotNull Player target,
            @NotNull TrainData trainData,
            Player owner,
            boolean markedCheater,
            @NotNull String datasetName
    ) {
        this.target = target;
        this.trainData = trainData;
        this.owner = owner;
        this.markedCheater = markedCheater;
        this.datasetName = datasetName;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}