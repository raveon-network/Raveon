package ru.raveon.utils.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class RotationRingBuffer<T> {
    private final Object[] frames;

    private int writeIndex;
    private int size;

    private int framesSinceLastSnapshot;
    private boolean firstSnapshotSent;

    public RotationRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be greater than zero");
        }

        this.frames = new Object[capacity];
    }

    public synchronized void addFrame(T frame) {
        frames[writeIndex] = Objects.requireNonNull(frame, "frame");
        writeIndex = (writeIndex + 1) % frames.length;

        if (size < frames.length) {
            size++;
        }

        framesSinceLastSnapshot++;
    }

    public synchronized boolean isFull() {
        return size == frames.length;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return frames.length;
    }

    public synchronized List<T> pollSnapshot(int step) {
        int safeStep = Math.max(1, step);

        if (!isFull()) {
            return null;
        }

        if (firstSnapshotSent && framesSinceLastSnapshot < safeStep) {
            return null;
        }

        List<T> snapshot = createSnapshot();
        firstSnapshotSent = true;
        framesSinceLastSnapshot = 0;
        return snapshot;
    }

    private List<T> createSnapshot() {
        List<T> snapshot = new ArrayList<>(size);

        int oldestIndex = size == frames.length ? writeIndex : 0;

        for (int i = 0; i < size; i++) {
            int index = (oldestIndex + i) % frames.length;
            @SuppressWarnings("unchecked")
            T frame = (T) frames[index];
            snapshot.add(frame);
        }

        return snapshot;
    }

    public synchronized void clear() {
        Arrays.fill(frames, null);
        writeIndex = 0;
        size = 0;
        framesSinceLastSnapshot = 0;
        firstSnapshotSent = false;
    }
}
