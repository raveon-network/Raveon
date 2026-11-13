package ru.raveon.manager.anticheat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class PacketWorkQueue<T> {
    private final int capacity;
    private final int batchSize;
    private final ArrayDeque<T> entries = new ArrayDeque<>();
    private boolean scheduled;
    private boolean resetNeeded;
    private boolean closed;
    private long generation;

    public PacketWorkQueue(int capacity, int batchSize) {
        if (capacity < 1 || batchSize < 1 || batchSize > capacity) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.batchSize = batchSize;
    }

    public synchronized Offer offer(T entry) {
        if (closed) return new Offer(false, false, generation);
        boolean overflow = entries.size() == capacity;
        if (overflow) {
            entries.clear();
            resetNeeded = true;
        }
        entries.addLast(entry);
        boolean start = !scheduled;
        if (start) generation++;
        scheduled = true;
        return new Offer(start, overflow, generation);
    }

    public synchronized boolean isCurrent(long expectedGeneration) {
        return !closed && scheduled && generation == expectedGeneration;
    }

    public synchronized Batch<T> takeBatch(long expectedGeneration) {
        if (!isCurrent(expectedGeneration)) return null;
        List<T> batch = new ArrayList<>(Math.min(batchSize, entries.size()));
        for (int i = 0; i < batchSize && !entries.isEmpty(); i++) batch.add(entries.removeFirst());
        boolean reset = resetNeeded;
        resetNeeded = false;
        return new Batch<>(List.copyOf(batch), reset);
    }

    public synchronized boolean completeBatch(long expectedGeneration) {
        if (!isCurrent(expectedGeneration)) return false;
        if (closed || entries.isEmpty()) {
            scheduled = false;
            return false;
        }
        return true;
    }

    public synchronized void reset() {
        entries.clear();
        resetNeeded = true;
    }

    /** Discards a failed task's work without permanently closing this connection's queue. */
    public synchronized boolean retry(long expectedGeneration) {
        if (!isCurrent(expectedGeneration)) return false;
        return restart();
    }

    /** Invalidates callbacks from the previous entity lifetime; never reopens a disconnected session. */
    public synchronized boolean restart() {
        if (closed) return false;
        generation++;
        entries.clear();
        scheduled = false;
        resetNeeded = true;
        return true;
    }

    public synchronized void close() {
        entries.clear();
        scheduled = false;
        resetNeeded = false;
        closed = true;
        generation++;
    }

    public synchronized int size() {
        return entries.size();
    }

    public record Offer(boolean schedule, boolean overflow, long generation) {}
    public record Batch<T>(List<T> entries, boolean reset) {}
}
