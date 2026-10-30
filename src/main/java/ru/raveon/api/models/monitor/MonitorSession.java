package ru.raveon.api.models.monitor;

import lombok.Data;

import java.util.Objects;
import java.util.UUID;

@Data
public final class MonitorSession {
    private final UUID targetId;
    private final String targetName;
    private final MonitorModelFilter modelFilter;

    public MonitorSession(UUID targetId, String targetName) {
        this(targetId, targetName, MonitorModelFilter.ALL);
    }

    public MonitorSession(UUID targetId, String targetName, MonitorModelFilter modelFilter) {
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.targetName = Objects.requireNonNull(targetName, "targetName");
        this.modelFilter = Objects.requireNonNull(modelFilter, "modelFilter");
    }

    private String lastMessage = "";
    private long lastSentTick = Long.MIN_VALUE;

    public boolean shouldSend(String message, long currentTick, long keepAliveTicks) {
        boolean changed = !message.equals(lastMessage);
        boolean keepAliveExpired = currentTick - lastSentTick >= keepAliveTicks;
        return changed || keepAliveExpired;
    }

    public void markSent(String message, long currentTick) {
        lastMessage = Objects.requireNonNull(message, "message");
        lastSentTick = currentTick;
    }
}
