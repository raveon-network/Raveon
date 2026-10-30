package ru.raveon.verification;

import ru.raveon.manager.anticheat.PacketWorkQueue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class PacketQueueVerification {
    public static void main(String[] args) throws Exception {
        PacketWorkQueue<Integer> queue = new PacketWorkQueue<>(4, 2);
        require(queue.offer(1).schedule(), "first task must schedule");
        require(!queue.offer(2).schedule(), "second task must share drain");
        require(!queue.offer(3).schedule(), "third task must share drain");
        require(queue.takeBatch().entries().equals(List.of(1, 2)), "packet order must be preserved");
        require(queue.completeBatch(), "remaining work must schedule a continuation");
        require(queue.takeBatch().entries().equals(List.of(3)), "continuation must preserve order");
        require(!queue.completeBatch(), "empty drain must release scheduling ownership");
        require(queue.offer(4).schedule(), "new burst must schedule");

        queue.offer(5);
        queue.offer(6);
        queue.offer(7);
        PacketWorkQueue.Offer overflow = queue.offer(8);
        require(overflow.overflow() && !overflow.schedule(), "overflow must reuse existing drain");
        PacketWorkQueue.Batch<Integer> reset = queue.takeBatch();
        require(reset.reset() && reset.entries().equals(List.of(8)), "overflow must discard a broken window");
        queue.offer(9);
        require(queue.completeBatch(), "arrivals during processing must not be stranded");
        require(queue.takeBatch().entries().equals(List.of(9)), "concurrent arrival must survive");
        require(!queue.completeBatch(), "drain must finish");

        queue.offer(10);
        queue.reset();
        queue.offer(11);
        require(queue.takeBatch().entries().equals(List.of(11)), "reload must remove old queued inputs");
        queue.close();
        require(!queue.offer(12).schedule() && queue.size() == 0, "closed player must reject work");
        require(!queue.completeBatch(), "closed player must not reschedule");

        PacketWorkQueue<Integer> concurrent = new PacketWorkQueue<>(16, 8);
        AtomicInteger scheduled = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] producers = new Thread[4];
        for (int index = 0; index < producers.length; index++) {
            final int offset = index * 1000;
            producers[index] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
                for (int value = 0; value < 1000; value++)
                    if (concurrent.offer(offset + value).schedule()) scheduled.incrementAndGet();
            });
            producers[index].start();
        }
        ready.await();
        start.countDown();
        for (Thread producer : producers) producer.join();
        require(scheduled.get() == 1, "concurrent producers must own exactly one drain");
        require(concurrent.size() <= 16, "queue capacity must hold under concurrency");
        require(concurrent.takeBatch().reset(), "overflow must invalidate feature windows");
        System.out.println("Packet queue verification passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
