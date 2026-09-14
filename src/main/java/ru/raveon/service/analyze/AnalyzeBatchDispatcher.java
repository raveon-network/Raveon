package ru.raveon.service.analyze;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.raveon.Raveon;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.SchedulerUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AnalyzeBatchDispatcher {
    private static final int MAX_BATCH_SIZE = 32;
    private static final long FLUSH_PERIOD_MS = 50L;
    private static final int BATCH_COUNT_SIZE = 2;
    private static final int BATCH_ITEM_HEADER_SIZE = 4;
    private static final byte[] RESPONSE_MAGIC = {'G', 'A', 'I', 'B'};

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RETRIES = 2;

    private final Plugin plugin;
    private final ChecksConfigManager configManager;
    private final HttpClient httpClient;
    private final ConcurrentLinkedQueue<PendingAnalyze> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final ScheduledExecutorService flusher;
    private volatile boolean stopped;

    public AnalyzeBatchDispatcher(Plugin plugin, ChecksConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "RaveonAI-analyze-flusher");
            thread.setDaemon(true);
            return thread;
        };
        this.flusher = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public void start() {
        flusher.scheduleAtFixedRate(this::safeFlush, FLUSH_PERIOD_MS, FLUSH_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        stopped = true;
        flusher.shutdownNow();
        queue.clear();
        queueSize.set(0);
    }

    public void enqueue(byte[] payload, RaveonPlayer raveonPlayer) {
        if (stopped) {
            return;
        }
        queue.add(new PendingAnalyze(payload, raveonPlayer));
        if (queueSize.incrementAndGet() >= MAX_BATCH_SIZE && !stopped) {
            try {
                flusher.execute(this::safeFlush);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    private void safeFlush() {
        try {
            flush();
        } catch (Throwable throwable) {
            Bukkit.getLogger().warning(
                    "[RaveonAI] Batch flush failed: " + throwable.getMessage()
            );
        }
    }

    private void flush() {
        final URI endpoint;
        try {
            endpoint = URI.create(configManager.getAnalyzeServer());
        } catch (RuntimeException exception) {
            int dropped = 0;
            while (queue.poll() != null) {
                queueSize.decrementAndGet();
                dropped++;
            }
            if (dropped > 0) {
                Bukkit.getLogger().warning(
                        "[RaveonAI] Invalid analyze_server URL, dropped "
                                + dropped + " pending windows: " + exception.getMessage()
                );
            }
            return;
        }

        while (true) {
            List<PendingAnalyze> batch = drainUpTo(MAX_BATCH_SIZE);
            if (batch.isEmpty()) {
                return;
            }
            sendBatch(endpoint, batch, 0);
        }
    }

    private List<PendingAnalyze> drainUpTo(int limit) {
        List<PendingAnalyze> items = new ArrayList<>(Math.min(limit, Math.max(queueSize.get(), 1)));
        for (int i = 0; i < limit; i++) {
            PendingAnalyze item = queue.poll();
            if (item == null) {
                break;
            }
            queueSize.decrementAndGet();
            items.add(item);
        }
        return items;
    }

    private void sendBatch(URI endpoint, List<PendingAnalyze> batch, int retry) {
        byte[] body = encodeFraming(batch);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/x-flatbuffers")
                .header("Accept", "application/x-flatbuffers")
                .header("X-Batch", "1")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> {
                    try {
                        if (throwable != null) {
                            Bukkit.getLogger().warning(
                                    "[RaveonAI] Analyze batch failed (items=" + batch.size()
                                            + ", retry=" + retry + "): " + throwable.getMessage()
                            );
                            retryBatch(endpoint, batch, retry);
                            return;
                        }
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            Bukkit.getLogger().warning(
                                    "[RaveonAI] Analyze server returned HTTP " + response.statusCode()
                                            + " (items=" + batch.size() + ", retry=" + retry + ")"
                            );
                            if (isRetryableStatus(response.statusCode())) {
                                retryBatch(endpoint, batch, retry);
                            }
                            return;
                        }
                        handleResponse(endpoint, batch, response, retry);
                    } catch (Throwable unexpected) {
                        Bukkit.getLogger().warning(
                                "[RaveonAI] Analyze batch callback failed: " + unexpected.getMessage()
                    );
                        retryBatch(endpoint, batch, retry);
                    }
                });
    }

    private void retryBatch(URI endpoint, List<PendingAnalyze> batch, int retry) {
        if (stopped || retry >= MAX_RETRIES) {
            return;
        }
        long delay = 100L * (retry + 1);
        flusher.schedule(() -> sendBatch(endpoint, batch, retry + 1), delay, TimeUnit.MILLISECONDS);
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private byte[] encodeFraming(List<PendingAnalyze> batch) {
        int totalSize = BATCH_COUNT_SIZE;
        for (PendingAnalyze item : batch) {
            totalSize += BATCH_ITEM_HEADER_SIZE + item.payload.length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) batch.size());
        for (PendingAnalyze item : batch) {
            buffer.putInt(item.payload.length);
            buffer.put(item.payload);
        }
        return buffer.array();
    }

    private void handleResponse(URI endpoint, List<PendingAnalyze> batch,
                                HttpResponse<byte[]> response, int retry) {
        final double[] probabilities;
        try {
            probabilities = decodeResponse(response.body(), batch.size());
        } catch (RuntimeException exception) {
            Bukkit.getLogger().warning(
                    "[RaveonAI] Batch response parse failed (items=" + batch.size()
                            + "): " + exception.getMessage()
            );
            retryBatch(endpoint, batch, retry);
            return;
        }

        if (stopped || !plugin.isEnabled()) {
            return;
        }

        for (int i = 0; i < batch.size(); i++) {
            final int index = i;
            Player player = batch.get(i).player.getBukkitPlayer();
            SchedulerUtils.runEntity(plugin, player, () -> Raveon.INSTANCE.getAiResultManager()
                    .handleAnalyzeResult(batch.get(index).player, probabilities[index]));
        }
    }

    private double[] decodeResponse(byte[] body, int expectedCount) {
        if (body == null || body.length < RESPONSE_MAGIC.length + BATCH_COUNT_SIZE) {
            throw new IllegalArgumentException("response is too short");
        }
        for (int i = 0; i < RESPONSE_MAGIC.length; i++) {
            if (body[i] != RESPONSE_MAGIC[i]) {
                throw new IllegalArgumentException("invalid response magic; expected GAIB");
            }
        }

        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(RESPONSE_MAGIC.length);
        int count = Short.toUnsignedInt(buffer.getShort());
        if (count != expectedCount) {
            throw new IllegalArgumentException(
                    "item count mismatch: expected " + expectedCount + ", got " + count
            );
        }
        if (buffer.remaining() < count * Double.BYTES) {
            throw new IllegalArgumentException("response body is truncated");
        }

        double[] probabilities = new double[count];
        for (int i = 0; i < count; i++) {
            double probability = buffer.getDouble();
            if (!Double.isFinite(probability)) {
                probability = 0.0D;
            }
            probabilities[i] = Math.max(0.0D, Math.min(1.0D, probability));
        }
        return probabilities;
    }

    private static final class PendingAnalyze {
        private final byte[] payload;
        private final RaveonPlayer player;

        private PendingAnalyze(byte[] payload, RaveonPlayer player) {
            this.payload = payload;
            this.player = player;
        }
    }
}
