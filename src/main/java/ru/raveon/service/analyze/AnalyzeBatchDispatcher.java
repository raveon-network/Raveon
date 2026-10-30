package ru.raveon.service.analyze;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import ru.raveon.Raveon;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.SchedulerUtils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class AnalyzeBatchDispatcher {
    private static final int MAX_BATCH_SIZE = 32;
    private static final int MAX_PENDING = 2048;
    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_IN_FLIGHT = 4;
    private static final int MAX_RETRIES = 2;
    private static final long MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final Plugin plugin;
    private final Supplier<ChecksConfigManager> configManager;
    private final HttpClient httpClient;
    private final ThreadPoolExecutor httpExecutor;
    private final ScheduledExecutorService flusher;
    private final Object lock = new Object();
    private final LinkedHashMap<PlayerModel, PendingAnalyze> queue = new LinkedHashMap<>();
    private final Map<PlayerModel, PendingAnalyze> active = new HashMap<>();
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE / 2);
    private final Set<Batch> batches = new HashSet<>();
    private int pendingBytes;
    private int inFlight;
    private volatile boolean stopped;
    private volatile long blockedUntil = Long.MIN_VALUE;

    public AnalyzeBatchDispatcher(Plugin plugin, Supplier<ChecksConfigManager> configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        httpExecutor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), task -> {
                    Thread thread = new Thread(task, "Raveon-http");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        httpClient = HttpClient.newBuilder().executor(httpExecutor)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(REQUEST_TIMEOUT).build();
        flusher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Raveon-analyze-flusher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        flusher.scheduleWithFixedDelay(this::safeFlush, 50L, 50L, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        stopped = true;
        flusher.shutdownNow();
        requests.forEach(request -> request.cancel(true));
        httpExecutor.shutdownNow();
        synchronized (lock) {
            queue.clear();
            active.clear();
            batches.clear();
            inFlight = 0;
            pendingBytes = 0;
        }
    }

    public void enqueue(byte[] payload, RaveonPlayer player) {
        enqueue(payload, player, ModelId.PRO);
    }

    public void enqueue(byte[] payload, RaveonPlayer player, ModelId model) {
        long now = System.nanoTime();
        ChecksConfigManager settings = configManager.get();
        if (stopped || now < blockedUntil || !settings.isModelEnabled(model)) return;
        if (payload == null || payload.length > MAX_PAYLOAD_BYTES) {
            warn("Analyze window exceeds the payload limit");
            return;
        }
        PlayerModel key = new PlayerModel(player.getUuid(), model);
        PendingAnalyze item = new PendingAnalyze(key, payload, player,
                player.getCheckManager().getGeneration(model), now, settings);
        synchronized (lock) {
            if (stopped) return;
            PendingAnalyze replaced = queue.remove(key);
            if (replaced != null) pendingBytes -= replaced.payload.length;
            if (queue.size() >= MAX_PENDING || pendingBytes + payload.length > MAX_PENDING_BYTES) {
                warn("Analyze queue is full; latest window dropped");
                return;
            }
            queue.put(key, item);
            pendingBytes += payload.length;
        }
    }

    private void safeFlush() {
        try {
            flush();
        } catch (RuntimeException exception) {
            warn("Analyze flush failed: " + exception.getMessage());
        }
    }

    private void flush() {
        if (stopped) return;
        long now = System.nanoTime();
        URI endpoint = URI.create(configManager.get().getAnalyzeServer());
        while (!stopped) {
            Batch batch = new Batch();
            synchronized (lock) {
                active.entrySet().removeIf(entry -> obsolete(entry.getValue(), now));
                Iterator<PendingAnalyze> iterator = queue.values().iterator();
                while (iterator.hasNext()) {
                    PendingAnalyze item = iterator.next();
                    if (obsolete(item, now)) {
                        iterator.remove();
                        pendingBytes -= item.payload.length;
                    }
                }
                if (now < blockedUntil || inFlight >= MAX_IN_FLIGHT) return;
                iterator = queue.values().iterator();
                while (iterator.hasNext() && batch.items.size() < MAX_BATCH_SIZE) {
                    PendingAnalyze item = iterator.next();
                    if (active.containsKey(item.key)) continue;
                    iterator.remove();
                    pendingBytes -= item.payload.length;
                    active.put(item.key, item);
                    batch.items.add(item);
                }
                if (batch.items.isEmpty()) return;
                batches.add(batch);
                inFlight++;
            }
            try {
                sendBatch(endpoint, batch, ModelWire.batch(
                        batch.items.stream().map(item -> item.payload).toList()), 0);
            } catch (RuntimeException error) {
                finish(batch, false);
                throw error;
            }
        }
    }

    private void sendBatch(URI endpoint, Batch batch, byte[] body, int retry) {
        if (stopped || System.nanoTime() < blockedUntil
                || batch.items.stream().anyMatch(item -> obsolete(item, System.nanoTime()))) {
            finish(batch, false);
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/x-flatbuffers")
                    .header("Accept", "application/x-flatbuffers")
                    .header("X-Batch", "1")
                    .header("X-Raveon-Protocol", "2")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            CompletableFuture<HttpResponse<byte[]>> future =
                    sendWithDeadline(request, 12 + batch.items.size() * 10);
            requests.add(future);
            future.whenComplete((response, error) -> {
                requests.remove(future);
                if (stopped) {
                    finish(batch, false);
                    return;
                }
                if (error != null) {
                    warn("Analyze request failed: " + error.getMessage());
                    retryOrFinish(endpoint, batch, body, retry, 0);
                    return;
                }
                int status = response.statusCode();
                if (status == 401 || status == 403) {
                    blockedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    synchronized (lock) {
                        queue.clear();
                        pendingBytes = 0;
                    }
                    warn("Analyze authorization rejected; requests paused for 60 seconds");
                    finish(batch, false);
                    return;
                }
                if (status < 200 || status >= 300) {
                    warn("Analyze server returned HTTP " + status);
                    long pause = retryAfter(response);
                    if ((status == 429 || status == 503) && pause > 0) {
                        blockedUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pause);
                        finish(batch, false);
                    } else if (isRetryableStatus(status))
                        retryOrFinish(endpoint, batch, body, retry, 0);
                    else finish(batch, false);
                    return;
                }
                try {
                    ModelWire.Result[] results = ModelWire.decode(
                            response.body(), batch.items.stream().map(item -> item.key.model).toList());
                    for (int index = 0; index < batch.items.size(); index++) {
                        PendingAnalyze item = batch.items.get(index);
                        ModelWire.Result result = results[index];
                        if (result.status() != 0) {
                            warn(result.model().displayName() + " inference is unavailable: " + result.status());
                            release(item);
                            continue;
                        }
                        apply(item, result.probability());
                    }
                    finish(batch, true);
                } catch (RuntimeException exception) {
                    warn("Analyze response rejected: " + exception.getMessage());
                    finish(batch, false);
                }
            });
            if (stopped) future.cancel(true);
        } catch (RuntimeException exception) {
            warn("Analyze request could not be sent: " + exception.getMessage());
            finish(batch, false);
        }
    }

    private CompletableFuture<HttpResponse<byte[]>> sendWithDeadline(HttpRequest request, int limit) {
        LimitedBodyHandler handler = new LimitedBodyHandler(limit);
        long startedAt = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> transport = httpClient.sendAsync(request, handler);
        CompletableFuture<HttpResponse<byte[]>> result = new CompletableFuture<>();
        try {
            var deadline = flusher.schedule(() -> result.completeExceptionally(
                    new HttpTimeoutException("Analyze response deadline exceeded")),
                    Math.max(0, REQUEST_TIMEOUT.toNanos() - (System.nanoTime() - startedAt)),
                    TimeUnit.NANOSECONDS);
            result.whenComplete((response, error) -> {
                deadline.cancel(false);
                if (error != null) {
                    handler.cancel(error);
                    transport.cancel(true);
                }
            });
            transport.whenComplete((response, error) -> {
                if (error == null) result.complete(response);
                else result.completeExceptionally(error);
            });
            return result;
        } catch (RuntimeException error) {
            handler.cancel(error);
            transport.cancel(true);
            throw error;
        }
    }

    private void apply(PendingAnalyze item, double probability) {
        if (!current(item)) {
            release(item);
            return;
        }
        Player player = item.player.getBukkitPlayer();
        try {
            SchedulerUtils.runEntity(plugin, player, () -> {
                try {
                    if (current(item)) {
                        item.player.getCheckManager().refreshSettings();
                        if (current(item))
                            Raveon.INSTANCE.getAiResultManager().handleAnalyzeResult(
                                    item.player, probability, item.key.model);
                    }
                } finally {
                    release(item);
                }
            }, () -> release(item));
        } catch (RuntimeException exception) {
            release(item);
            warn("Analyze result could not be scheduled: " + exception.getMessage());
        }
    }

    private boolean current(PendingAnalyze item) {
        if (stopped || !plugin.isEnabled() || obsolete(item, System.nanoTime())
                || Raveon.INSTANCE.getPlayerDataManager().getPlayer(item.key.uuid) != item.player)
            return false;
        synchronized (lock) {
            return active.get(item.key) == item;
        }
    }

    private boolean obsolete(PendingAnalyze item, long now) {
        ChecksConfigManager current = configManager.get();
        return now - item.createdAt > MAX_AGE_NANOS || current != item.configuration
                || !current.isModelEnabled(item.key.model)
                || item.player.getCheckManager().getGeneration(item.key.model) != item.generation;
    }

    private void release(PendingAnalyze item) {
        synchronized (lock) {
            active.remove(item.key, item);
        }
    }

    private void finish(Batch batch, boolean scheduled) {
        synchronized (lock) {
            if (!batches.remove(batch)) return;
            if (!scheduled) batch.items.forEach(item -> active.remove(item.key, item));
            inFlight--;
        }
    }

    private void retryOrFinish(
            URI endpoint, Batch batch, byte[] body, int retry, long retryAfterMillis) {
        if (stopped || retry >= MAX_RETRIES || System.nanoTime() < blockedUntil) {
            finish(batch, false);
            return;
        }
        long delay = Math.max(retryAfterMillis,
                250L * (1L << retry) + ThreadLocalRandom.current().nextLong(100));
        try {
            flusher.schedule(() -> sendBatch(endpoint, batch, body, retry + 1), delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            finish(batch, false);
        }
    }

    private long retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("0");
        try {
            return Math.min(3600L, Math.max(0, Long.parseLong(value))) * 1000L;
        } catch (NumberFormatException exception) {
            try {
                long delay = java.time.Duration.between(java.time.Instant.now(),
                        java.time.ZonedDateTime.parse(value,
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                return Math.max(0, Math.min(3_600_000L, delay));
            } catch (java.time.DateTimeException invalid) {
                return 0;
            }
        }
    }

    private boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504;
    }

    private void warn(String message) {
        long now = System.nanoTime();
        long previous = lastWarning.get();
        if (now - previous >= TimeUnit.SECONDS.toNanos(5)
                && lastWarning.compareAndSet(previous, now))
            plugin.getLogger().warning(message);
    }

    static HttpResponse.BodyHandler<byte[]> limitedBody(int limit) {
        return new LimitedBodyHandler(limit);
    }

    private static final class LimitedBodyHandler implements HttpResponse.BodyHandler<byte[]> {
        private final int limit;
        private final AtomicReference<LimitedBodySubscriber> subscriber = new AtomicReference<>();
        private volatile Throwable cancellation;

        private LimitedBodyHandler(int limit) {
            this.limit = limit;
        }

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
            LimitedBodySubscriber body = new LimitedBodySubscriber(
                    limit, info.statusCode() < 200 || info.statusCode() >= 300);
            subscriber.set(body);
            Throwable error = cancellation;
            if (error != null) body.cancel(error);
            return body;
        }

        private void cancel(Throwable error) {
            cancellation = error;
            LimitedBodySubscriber body = subscriber.get();
            if (body != null) body.cancel(error);
        }
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final boolean headersOnly;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBodySubscriber(int limit) {
            this(limit, false);
        }

        LimitedBodySubscriber(int limit, boolean headersOnly) {
            this.limit = limit;
            this.headersOnly = headersOnly;
        }

        @Override
        public CompletionStage<byte[]> getBody() { return result; }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (result.isDone()) {
                subscription.cancel();
                return;
            }
            if (headersOnly) {
                result.complete(new byte[0]);
                subscription.cancel();
            } else subscription.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - body.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalArgumentException("Analyze response exceeds byte limit"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                body.writeBytes(bytes);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) { result.completeExceptionally(error); }

        @Override
        public synchronized void onComplete() { result.complete(body.toByteArray()); }

        synchronized void cancel(Throwable error) {
            if (subscription != null) subscription.cancel();
            result.completeExceptionally(error);
        }
    }

    private static final class Batch {
        private final List<PendingAnalyze> items = new ArrayList<>(MAX_BATCH_SIZE);
    }

    private record PlayerModel(UUID uuid, ModelId model) {}

    private record PendingAnalyze(
            PlayerModel key, byte[] payload, RaveonPlayer player, long generation, long createdAt,
            ChecksConfigManager configuration) {}
}
