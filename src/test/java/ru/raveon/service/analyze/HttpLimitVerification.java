package ru.raveon.service.analyze;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HttpLimitVerification {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("deadline-only")) {
            verifyBodyDeadline();
            System.out.println("Full HTTP response deadline passed on Java " + System.getProperty("java.version"));
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        var subscriber = new AnalyzeBatchDispatcher.LimitedBodySubscriber(32);
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long count) {}
            public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[31])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[2])));
        require(cancelled.get(), "Oversized streamed response must cancel upstream");
        try {
            subscriber.getBody().toCompletableFuture().join();
            throw new AssertionError("Oversized streamed response was accepted");
        } catch (CompletionException expected) {
            require(expected.getCause() instanceof IllegalArgumentException, "Wrong limit failure");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/valid", exchange -> {
            byte[] bytes = new byte[32];
            Arrays.fill(bytes, (byte) 7);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        });
        server.createContext("/rejected", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "120");
            exchange.sendResponseHeaders(429, 0);
            try (var stream = exchange.getResponseBody()) {
                stream.write(new byte[8192]);
            } catch (IOException expected) {
                exchange.close();
            }
        });
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var stream = exchange.getResponseBody()) {
                stream.write(new byte[8192]);
            } catch (IOException expected) {
                exchange.close();
            }
        });
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            byte[] body = client.send(HttpRequest.newBuilder(base.resolve("/valid"))
                    .timeout(Duration.ofSeconds(2)).build(), AnalyzeBatchDispatcher.limitedBody(32)).body();
            require(body.length == 32 && body[0] == 7, "Exact-limit valid response changed");
            var rejected = client.send(HttpRequest.newBuilder(base.resolve("/rejected"))
                    .timeout(Duration.ofSeconds(2)).build(), AnalyzeBatchDispatcher.limitedBody(32));
            require(rejected.statusCode() == 429 && rejected.body().length == 0
                    && rejected.headers().firstValue("Retry-After").orElse("").equals("120"),
                    "Large error body hid status or backoff headers");
            try {
                client.send(HttpRequest.newBuilder(base.resolve("/large"))
                        .timeout(Duration.ofSeconds(2)).build(), AnalyzeBatchDispatcher.limitedBody(32));
                throw new AssertionError("Oversized HTTP body was accepted");
            } catch (IOException | IllegalArgumentException expected) {
                require(expected.getMessage().contains("byte limit"), "Wrong HTTP limit failure");
            }
        } finally {
            server.stop(0);
        }
        verifyBodyDeadline();
        verifyQueueAndConcurrency();
        verifyResultLifecycle();
        verifyModelSwitches();
        System.out.println("HTTP response boundary, coalescing, queue capacity and localhost concurrency, response lifecycle and model enable gates passed");
    }

    private static void verifyBodyDeadline() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var valid = new java.util.concurrent.atomic.AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        var healthy = new AtomicBoolean();
        var handlers = java.util.concurrent.Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "http-deadline-fixture");
            thread.setDaemon(true);
            return thread;
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/inference", exchange -> {
            byte[] input = exchange.getRequestBody().readAllBytes();
            int count = Short.toUnsignedInt(ByteBuffer.wrap(input)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort());
            ByteBuffer response = ByteBuffer.allocate(6 + count * 8)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            response.put(new byte[] {'G', 'A', 'I', 'B'}).putShort((short) count);
            exchange.sendResponseHeaders(200, response.capacity());
            try (var output = exchange.getResponseBody()) {
                if (healthy.get()) {
                    output.write(response.array());
                    valid.incrementAndGet();
                } else {
                    output.write(response.array(), 0, 1);
                    output.flush();
                    received.incrementAndGet();
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    output.write(response.array(), 1, response.capacity() - 1);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                exchange.close();
            }
        });
        var config = configuration("http://127.0.0.1:" + server.getAddress().getPort()
                + "/inference", true, true, true);
        var plugin = proxy(org.bukkit.plugin.Plugin.class, (object, name, args) -> switch (name) {
            case "isEnabled" -> false;
            case "getLogger" -> java.util.logging.Logger.getLogger("HttpDeadlineVerification");
            default -> throw new UnsupportedOperationException(name);
        });
        var dispatcher = new AnalyzeBatchDispatcher(plugin, () -> config);
        var players = new java.util.ArrayList<ru.raveon.player.RaveonPlayer>();
        try {
            server.start();
            for (int i = 0; i < 128; i++) {
                var player = player(unsafe);
                players.add(player);
                dispatcher.enqueue(new byte[] {1}, player);
            }
            dispatcher.start();
            waitFor(() -> received.get() == 4);
            for (var player : players) player.getCheckManager().getGenerations()
                    .get(ru.raveon.model.ModelId.PRO).incrementAndGet();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(7);
            while (inFlight(dispatcher) != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            require(inFlight(dispatcher) == 0, "Stalled response bodies exhausted all HTTP slots");
            require(((java.util.Set<?>) get(dispatcher, "requests")).isEmpty(),
                    "Timed out HTTP transport is still tracked");
            healthy.set(true);
            dispatcher.enqueue(new byte[] {1}, player(unsafe));
            waitFor(() -> valid.get() == 1 && inFlight(dispatcher) == 0);
            release.countDown();
            Thread.sleep(100);
            require(inFlight(dispatcher) == 0, "Late completion released an HTTP slot twice");
            healthy.set(false);
            dispatcher.enqueue(new byte[] {1}, player(unsafe));
            waitFor(() -> received.get() >= 5);
            dispatcher.stop();
            require(inFlight(dispatcher) == 0, "Shutdown retained an HTTP batch");
        } finally {
            dispatcher.stop();
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
        var cancelled = new AtomicBoolean();
        var body = new AnalyzeBatchDispatcher.LimitedBodySubscriber(32);
        body.cancel(new java.net.http.HttpTimeoutException("deadline"));
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) { throw new AssertionError("Cancelled body requested bytes"); }
            public void cancel() { cancelled.set(true); }
        });
        require(cancelled.get(), "Late HTTP body subscription was not cancelled");
    }

    private static void verifyQueueAndConcurrency() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService handlers = java.util.concurrent.Executors.newFixedThreadPool(8, task -> {
            Thread thread = new Thread(task, "http-boundary-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(handlers);
        server.createContext("/blocked", exchange -> {
            requests.incrementAndGet();
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            byte[] request = exchange.getRequestBody().readAllBytes();
            int count = ByteBuffer.wrap(request).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort() & 65535;
            try {
                if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("HTTP fixture timed out");
                ByteBuffer body = ByteBuffer.allocate(6 + 8 * count).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                body.put(new byte[] {'G', 'A', 'I', 'B'}).putShort((short) count);
                exchange.sendResponseHeaders(200, body.capacity());
                exchange.getResponseBody().write(body.array());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
                active.decrementAndGet();
            }
        });
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var config = configuration("http://127.0.0.1:" + server.getAddress().getPort() + "/blocked", true, true, true);
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(), new Class<?>[] {org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> false;
                    case "getLogger" -> java.util.logging.Logger.getLogger("HttpBoundaryVerification");
                    case "getName", "toString" -> "HttpBoundaryVerification";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AnalyzeBatchDispatcher dispatcher = new AnalyzeBatchDispatcher(plugin, () -> config);
        try {
            server.start();
            var same = player(unsafe);
            for (int i = 0; i < 100; i++) dispatcher.enqueue(new byte[] {(byte) i}, same);
            require(((java.util.Map<?, ?>) get(dispatcher, "queue")).size() == 1,
                    "Repeated windows were not coalesced by player/model");
            for (int i = 0; i < 3000; i++) dispatcher.enqueue(new byte[] {1}, player(unsafe));
            require(((java.util.Map<?, ?>) get(dispatcher, "queue")).size() == 2048,
                    "Pending window count is not bounded");
            dispatcher.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (requests.get() < 4 && System.nanoTime() < deadline) Thread.sleep(10);
            require(requests.get() == 4, "Did not issue four available batches");
            Thread.sleep(100);
            require(maximum.get() == 4 && requests.get() == 4, "HTTP in-flight limit was exceeded");
            require(((java.util.Map<?, ?>) get(dispatcher, "queue")).size() <= 2048,
                    "Queue exceeded capacity after flushing");
        } finally {
            dispatcher.stop();
            require(inFlight(dispatcher) == 0, "Shutdown retained in-flight batches");
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyResultLifecycle() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var originalPlugin = ru.raveon.Raveon.INSTANCE;
        var serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object originalServer = serverField.get(null);
        var callbacks = new java.util.ArrayDeque<Runnable>();
        var enabled = new AtomicBoolean(true);
        var applied = new java.util.ArrayList<Double>();
        var scheduler = proxy(org.bukkit.scheduler.BukkitScheduler.class, (object, name, args) -> {
            if (name.equals("runTask")) {
                callbacks.add((Runnable) args[1]);
                return null;
            }
            throw new UnsupportedOperationException(name);
        });
        serverField.set(null, proxy(org.bukkit.Server.class, (object, name, args) -> {
            if (name.equals("getScheduler")) return scheduler;
            throw new UnsupportedOperationException(name);
        }));
        var plugin = proxy(org.bukkit.plugin.Plugin.class, (object, name, args) -> switch (name) {
            case "isEnabled" -> enabled.get();
            case "getLogger" -> java.util.logging.Logger.getLogger("HttpLifecycleVerification");
            default -> throw new UnsupportedOperationException(name);
        });
        var config = configuration("http://127.0.0.1:1/inference", true, true, true);
        var configRef = new java.util.concurrent.atomic.AtomicReference<>(config);
        var raveon = (ru.raveon.Raveon) unsafe.allocateInstance(ru.raveon.Raveon.class);
        var players = new ru.raveon.manager.anticheat.PlayerDataManager();
        set(raveon, "playerDataManager", players);
        set(raveon, "configuration", new ru.raveon.config.RuntimeConfiguration(null, config, null, null, null));
        set(raveon, "aiResultManager", new ru.raveon.manager.AIResultManager() {
            @Override
            public void handleAnalyzeResult(ru.raveon.player.RaveonPlayer player, double probability,
                    ru.raveon.model.ModelId model) {
                applied.add(probability);
            }
        });
        ru.raveon.Raveon.INSTANCE = raveon;
        var currentPlayer = player(unsafe);
        set(currentPlayer, "bukkitPlayer", proxy(org.bukkit.entity.Player.class,
                (object, name, args) -> {
                    if (name.equals("isValid")) return true;
                    throw new UnsupportedOperationException(name);
                }));
        set(currentPlayer.getCheckManager(), "activeConfig", config);
        var online = (java.util.Map<java.util.UUID, ru.raveon.player.RaveonPlayer>) get(players, "playerDataMap");
        online.put(currentPlayer.getUuid(), currentPlayer);
        var generations = (java.util.Map<ru.raveon.model.ModelId, java.util.concurrent.atomic.AtomicLong>)
                get(currentPlayer.getCheckManager(), "generations");
        var dispatcher = new AnalyzeBatchDispatcher(plugin, configRef::get);
        try {
            var queue = (java.util.Map<Object, Object>) get(dispatcher, "queue");
            var active = (java.util.Map<Object, Object>) get(dispatcher, "active");
            dispatcher.enqueue(new byte[] {1}, currentPlayer);
            Object key = queue.keySet().iterator().next();
            Object first = queue.remove(key);
            active.put(key, first);
            var apply = AnalyzeBatchDispatcher.class.getDeclaredMethod("apply", first.getClass(), double.class);
            apply.setAccessible(true);
            apply.invoke(dispatcher, first, .1);
            require(applied.isEmpty() && callbacks.size() == 1, "response bypassed owner scheduler");
            callbacks.remove().run();
            require(applied.equals(List.of(.1)) && active.isEmpty(), "fresh response was not applied once");
            dispatcher.enqueue(new byte[] {2}, currentPlayer);
            Object pending = queue.remove(key);
            active.put(key, pending);
            apply.invoke(dispatcher, pending, .2);
            generations.get(ru.raveon.model.ModelId.PRO).incrementAndGet();
            callbacks.remove().run();
            require(applied.equals(List.of(.1)), "response applied after generation changed");
            dispatcher.enqueue(new byte[] {3}, currentPlayer);
            pending = queue.remove(key);
            active.put(key, pending);
            apply.invoke(dispatcher, pending, .3);
            online.put(currentPlayer.getUuid(), player(unsafe));
            callbacks.remove().run();
            require(applied.equals(List.of(.1)), "old connection response applied after reconnect");
            online.put(currentPlayer.getUuid(), currentPlayer);
            dispatcher.enqueue(new byte[] {4}, currentPlayer);
            Object old = queue.remove(key);
            active.put(key, old);
            apply.invoke(dispatcher, old, .4);
            dispatcher.enqueue(new byte[] {5}, currentPlayer);
            Object latest = queue.remove(key);
            active.put(key, latest);
            callbacks.remove().run();
            require(applied.equals(List.of(.1)) && active.get(key) == latest,
                    "old callback applied or removed replacement window");
            apply.invoke(dispatcher, latest, .5);
            callbacks.remove().run();
            require(applied.equals(List.of(.1, .5)), "replacement window was lost");
            var constructor = first.getClass().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object expired = constructor.newInstance(key, new byte[] {6}, currentPlayer,
                    generations.get(ru.raveon.model.ModelId.PRO).get(),
                    System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(11), config);
            active.put(key, expired);
            apply.invoke(dispatcher, expired, .6);
            require(callbacks.isEmpty() && active.isEmpty(), "expired response was scheduled");
            dispatcher.enqueue(new byte[] {8}, currentPlayer);
            pending = queue.remove(key);
            active.put(key, pending);
            apply.invoke(dispatcher, pending, .8);
            configRef.set(configuration("http://127.0.0.1:1/inference", false, true, true));
            callbacks.remove().run();
            require(applied.equals(List.of(.1, .5)), "response applied after master switch changed");
            configRef.set(config);
            dispatcher.enqueue(new byte[] {7}, currentPlayer);
            pending = queue.remove(key);
            active.put(key, pending);
            apply.invoke(dispatcher, pending, .7);
            dispatcher.stop();
            callbacks.remove().run();
            require(applied.equals(List.of(.1, .5)), "response applied after shutdown");
        } finally {
            dispatcher.stop();
            serverField.set(null, originalServer);
            ru.raveon.Raveon.INSTANCE = originalPlugin;
        }
    }

    private static ru.raveon.config.anticheat.ChecksConfigManager configuration(
            String endpoint, boolean enabled, boolean pro, boolean ultra) throws Exception {
        var folder = java.nio.file.Files.createTempDirectory("raveon-http-config");
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("analyze.enabled", enabled);
        yaml.set("analyze.server", endpoint);
        yaml.set("analyze.models.pro.enabled", pro);
        yaml.set("analyze.models.ultra.enabled", ultra);
        yaml.set("analyze.worldguard.enabled", false);
        yaml.save(folder.resolve("checks.yml").toFile());
        var plugin = proxy(org.bukkit.plugin.Plugin.class, (object, name, args) -> switch (name) {
            case "getDataFolder" -> folder.toFile();
            case "getLogger" -> java.util.logging.Logger.getLogger("HttpConfigVerification");
            default -> throw new UnsupportedOperationException(name);
        });
        return new ru.raveon.config.anticheat.ChecksConfigManager(plugin);
    }

    private static void verifyModelSwitches() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var received = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inference", exchange -> {
            ByteBuffer input = ByteBuffer.wrap(exchange.getRequestBody().readAllBytes())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int count = Short.toUnsignedInt(input.getShort());
            int[] models = new int[count];
            for (int i = 0; i < count; i++) {
                int length = input.getInt();
                models[i] = Byte.toUnsignedInt(input.get());
                input.position(input.position() + length - 1);
                received.add(models[i]);
            }
            ByteBuffer output = ByteBuffer.allocate(12 + count * 10)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            output.put(new byte[] {'G', 'A', 'I', 'B'}).putShort((short) count);
            for (int i = 0; i < count; i++) output.putDouble(.5);
            output.put(new byte[] {'R', 'V', 'M', '2'}).putShort((short) count);
            for (int model : models) output.put((byte) model).put((byte) 0);
            exchange.sendResponseHeaders(200, output.capacity());
            try (var body = exchange.getResponseBody()) {
                body.write(output.array());
            }
        });
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/inference";
        var both = configuration(endpoint, true, true, true);
        var off = configuration(endpoint, false, true, true);
        var neither = configuration(endpoint, true, false, false);
        var proOnly = configuration(endpoint, true, true, false);
        var ultraOnly = configuration(endpoint, true, false, true);
        var current = new java.util.concurrent.atomic.AtomicReference<>(off);
        var plugin = proxy(org.bukkit.plugin.Plugin.class, (object, name, args) -> switch (name) {
            case "isEnabled" -> false;
            case "getLogger" -> java.util.logging.Logger.getLogger("HttpSwitchVerification");
            default -> throw new UnsupportedOperationException(name);
        });
        var dispatcher = new AnalyzeBatchDispatcher(plugin, current::get);
        var player = player(unsafe);
        var flush = AnalyzeBatchDispatcher.class.getDeclaredMethod("flush");
        flush.setAccessible(true);
        try {
            server.start();
            var queue = (java.util.Map<?, ?>) get(dispatcher, "queue");
            for (var disabled : List.of(off, neither)) {
                current.set(disabled);
                dispatcher.enqueue(new byte[] {1}, player, ru.raveon.model.ModelId.PRO);
                dispatcher.enqueue(new byte[] {2}, player, ru.raveon.model.ModelId.ULTRA);
                flush.invoke(dispatcher);
                require(queue.isEmpty() && received.isEmpty(), "disabled analysis submitted requests");
            }
            current.set(both);
            dispatcher.enqueue(new byte[] {1}, player, ru.raveon.model.ModelId.PRO);
            dispatcher.enqueue(new byte[] {2}, player, ru.raveon.model.ModelId.ULTRA);
            require(queue.size() == 2, "enabled models did not queue independently");
            current.set(neither);
            flush.invoke(dispatcher);
            require(queue.isEmpty() && ((Integer) get(dispatcher, "pendingBytes")) == 0 && received.isEmpty(),
                    "disabled queued windows were sent or retained");

            current.set(proOnly);
            dispatcher.enqueue(new byte[] {1}, player, ru.raveon.model.ModelId.PRO);
            dispatcher.enqueue(new byte[] {2}, player, ru.raveon.model.ModelId.ULTRA);
            flush.invoke(dispatcher);
            waitFor(() -> received.size() == 1 && inFlight(dispatcher) == 0);
            require(received.equals(List.of(1)), "disabled Ultra sent a request");

            current.set(ultraOnly);
            dispatcher.enqueue(new byte[] {1}, player, ru.raveon.model.ModelId.PRO);
            dispatcher.enqueue(new byte[] {2}, player, ru.raveon.model.ModelId.ULTRA);
            flush.invoke(dispatcher);
            waitFor(() -> received.size() == 2 && inFlight(dispatcher) == 0);
            require(received.equals(List.of(1, 2)), "disabled Pro sent a request");
        } finally {
            dispatcher.stop();
            server.stop(0);
        }
    }

    private static int inFlight(AnalyzeBatchDispatcher dispatcher) {
        try {
            Object lock = get(dispatcher, "lock");
            synchronized (lock) {
                return (Integer) get(dispatcher, "inFlight");
            }
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        require(condition.getAsBoolean(), "HTTP switch verification timed out");
    }

    private interface Invocation {
        Object invoke(Object object, String name, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation handler) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (object, method, args) -> handler.invoke(object, method.getName(), args)));
    }

    private static ru.raveon.player.RaveonPlayer player(sun.misc.Unsafe unsafe) throws Exception {
        var player = (ru.raveon.player.RaveonPlayer) unsafe.allocateInstance(ru.raveon.player.RaveonPlayer.class);
        var checks = (ru.raveon.manager.anticheat.CheckManager)
                unsafe.allocateInstance(ru.raveon.manager.anticheat.CheckManager.class);
        java.util.Map<ru.raveon.model.ModelId, java.util.concurrent.atomic.AtomicLong> generations =
                new java.util.EnumMap<>(ru.raveon.model.ModelId.class);
        for (var model : ru.raveon.model.ModelId.values())
            generations.put(model, new java.util.concurrent.atomic.AtomicLong());
        set(checks, "generations", generations);
        set(player, "uuid", java.util.UUID.randomUUID());
        set(player, "checkManager", checks);
        return player;
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        java.lang.reflect.Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Object get(Object instance, String name) throws Exception {
        java.lang.reflect.Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
