package ru.raveon.manager.anticheat;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** UUID-only state: no player, channel, expiry, capacity eviction or offline decay. */
public final class SessionBufferCache {
    private final ConcurrentHashMap<UUID, ModelDetectionState> states = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    public SessionBufferCache() { this(System::nanoTime); }
    public SessionBufferCache(java.util.function.LongSupplier clock) { this.clock = clock; }
    public ModelDetectionState get(UUID player) { return states.computeIfAbsent(player, ignored -> new ModelDetectionState(clock)); }
    public void clear() { states.clear(); }
    public int size() { return states.size(); }
}
