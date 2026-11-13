package ru.raveon.manager.anticheat;

import java.util.List;

/** Dependency-free regression checks for scheduler cancellation and entity replacement. */
public final class PacketWorkQueueRegression {
    public static void main(String[] args) {
        retryAfterRetirement();
        staleCallbacksCannotAffectReplacement();
        respawnDiscardsOldWork();
        disconnectIsPermanent();
        completedTaskCannotCancelNextTask();
        reloadKeepsScheduledTask();
        overflowResetsWindowAndKeepsLatestPacket();
        failedBatchDiscardsRemainingWork();
        System.out.println("PacketWorkQueue: 8 regression checks passed");
    }

    private static void retryAfterRetirement() {
        var queue = new PacketWorkQueue<String>(4, 2);
        var old = queue.offer("old");
        check(queue.retry(old.generation()), "retirement must release the scheduled task");
        var next = queue.offer("new");
        check(next.schedule(), "next packet must schedule processing again");
        var batch = queue.takeBatch(next.generation());
        check(batch.reset() && batch.entries().equals(List.of("new")), "retry must reset and discard old work");
        check(!queue.completeBatch(next.generation()), "empty queue must become idle");
    }

    private static void staleCallbacksCannotAffectReplacement() {
        var queue = new PacketWorkQueue<String>(4, 2);
        long old = queue.offer("old").generation();
        queue.retry(old);
        long current = queue.offer("new").generation();
        check(!queue.retry(old), "duplicate retired callback must not cancel the new task");
        check(queue.takeBatch(old) == null, "stale task must not consume new packets");
        check(!queue.completeBatch(old), "stale completion must not reschedule the new task");
        check(queue.isCurrent(current), "new task must remain scheduled");
        check(queue.takeBatch(current).entries().equals(List.of("new")), "new packet must survive stale callbacks");
    }

    private static void respawnDiscardsOldWork() {
        var queue = new PacketWorkQueue<Integer>(4, 2);
        long old = queue.offer(1).generation();
        check(queue.restart(), "live session must restart after respawn");
        long current = queue.offer(2).generation();
        check(queue.takeBatch(old) == null && !queue.retry(old), "old entity callbacks must be invalid");
        var batch = queue.takeBatch(current);
        check(batch.reset() && batch.entries().equals(List.of(2)), "respawn must start with a fresh window");
    }

    private static void disconnectIsPermanent() {
        var queue = new PacketWorkQueue<Integer>(4, 2);
        long old = queue.offer(1).generation();
        queue.close();
        queue.reset();
        check(!queue.restart() && !queue.retry(old), "respawn and retry must not reopen a disconnected session");
        check(!queue.offer(2).schedule() && queue.size() == 0, "closed session must reject packets");
        check(queue.takeBatch(old) == null, "closed session must reject callbacks");
    }

    private static void completedTaskCannotCancelNextTask() {
        var queue = new PacketWorkQueue<Integer>(4, 2);
        long old = queue.offer(1).generation();
        queue.takeBatch(old);
        check(!queue.completeBatch(old), "first task must finish");
        long current = queue.offer(2).generation();
        check(current != old && !queue.retry(old), "each new task must have a distinct generation");
        check(queue.takeBatch(current).entries().equals(List.of(2)), "late callback must not discard work");
    }

    private static void reloadKeepsScheduledTask() {
        var queue = new PacketWorkQueue<Integer>(4, 1);
        long current = queue.offer(1).generation();
        queue.reset();
        check(!queue.offer(2).schedule(), "reload must retain the already scheduled task");
        var batch = queue.takeBatch(current);
        check(batch.reset() && batch.entries().equals(List.of(2)), "reload must discard pre-reload packets");
        queue.offer(3);
        check(queue.completeBatch(current), "packets arriving during a batch must trigger another batch");
        check(queue.takeBatch(current).entries().equals(List.of(3)), "follow-up batch must retain order");
    }

    private static void overflowResetsWindowAndKeepsLatestPacket() {
        var queue = new PacketWorkQueue<Integer>(2, 1);
        long current = queue.offer(1).generation();
        queue.offer(2);
        check(queue.offer(3).overflow(), "full queue must report overflow");
        var batch = queue.takeBatch(current);
        check(batch.reset() && batch.entries().equals(List.of(3)), "overflow must preserve only fresh work");
    }

    private static void failedBatchDiscardsRemainingWork() {
        var queue = new PacketWorkQueue<Integer>(4, 1);
        long old = queue.offer(1).generation();
        queue.offer(2);
        queue.takeBatch(old);
        queue.retry(old);
        long current = queue.offer(3).generation();
        check(!queue.completeBatch(old), "failed batch finally block must not reschedule old work");
        var batch = queue.takeBatch(current);
        check(batch.reset() && batch.entries().equals(List.of(3)), "processing error must recover with a fresh batch");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
