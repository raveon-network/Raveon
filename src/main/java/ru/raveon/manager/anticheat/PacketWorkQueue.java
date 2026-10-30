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

    public PacketWorkQueue(int capacity, int batchSize) {
        if (capacity < 1 || batchSize < 1 || batchSize > capacity) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.batchSize = batchSize;
    }

    public synchronized Offer offer(T entry) {
        if (closed) return new Offer(false, false);
        boolean overflow = entries.size() == capacity;
        if (overflow) {
            entries.clear();
            resetNeeded = true;
        }
        entries.addLast(entry);
        boolean start = !scheduled;
        scheduled = true;
        return new Offer(start, overflow);
    }

    public synchronized Batch<T> takeBatch() {
        List<T> batch = new ArrayList<>(Math.min(batchSize, entries.size()));
        for (int i = 0; i < batchSize && !entries.isEmpty(); i++) batch.add(entries.removeFirst());
        boolean reset = resetNeeded;
        resetNeeded = false;
        return new Batch<>(List.copyOf(batch), reset);
    }

    public synchronized boolean completeBatch() {
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

    public synchronized void close() {
        entries.clear();
        scheduled = false;
        resetNeeded = false;
        closed = true;
    }

    public synchronized int size() {
        return entries.size();
    }

    public record Offer(boolean schedule, boolean overflow) {}
    public record Batch<T>(List<T> entries, boolean reset) {}
}
