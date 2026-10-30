package ru.raveon.checks.data;

import ru.raveon.api.models.UltraFrame;
import ru.raveon.utils.math.RotationRingBuffer;

import java.util.List;

/** Per-player Ultra session; identity protects queued callbacks after reset. */
public final class UltraData {
    private UltraState state = new UltraState();
    private RotationRingBuffer<UltraFrame> history = new RotationRingBuffer<>(64);
    private long combatUntil;
    private boolean active;

    public synchronized UltraState activate() {
        active = true;
        return state;
    }

    public synchronized boolean isCurrent(UltraState expected) {
        return active && state == expected;
    }

    public synchronized void reset() {
        state = new UltraState();
        history.clear();
        combatUntil = 0;
        active = false;
    }

    public synchronized boolean isCombatActive() {
        return System.currentTimeMillis() <= combatUntil;
    }

    public synchronized void extendCombat(long millis) {
        combatUntil = System.currentTimeMillis() + millis;
    }

    public synchronized void clearHistory() {
        history.clear();
    }

    public synchronized void resize(int capacity) {
        if (history.capacity() != capacity) history = new RotationRingBuffer<>(capacity);
    }

    public synchronized void push(UltraFrame frame) {
        history.addFrame(frame);
    }

    public synchronized List<UltraFrame> pollSnapshot(int step) {
        return history.pollSnapshot(step);
    }
}
