package ru.raveon.api.events;

import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import ru.raveon.api.models.data.TrainData;

@Getter
public final class EndTrainEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player target;
    private final TrainData trainData;
    private final Player owner;
    private final boolean markedCheater;
    private final int totalFramesCollected;
    private final int datasetsUploaded;

    public EndTrainEvent(
            @NotNull Player target,
            @NotNull TrainData trainData,
            Player owner,
            boolean markedCheater,
            int totalFramesCollected,
            int datasetsUploaded
    ) {
        this.target = target;
        this.trainData = trainData;
        this.owner = owner;
        this.markedCheater = markedCheater;
        this.totalFramesCollected = totalFramesCollected;
        this.datasetsUploaded = datasetsUploaded;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}