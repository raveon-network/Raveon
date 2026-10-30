package ru.raveon.verification;

import ru.raveon.Raveon;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.api.models.UltraFrame;
import ru.raveon.api.models.data.TrainData;
import ru.raveon.config.datacollect.DataCollectConfigManager;
import ru.raveon.model.ModelId;
import ru.raveon.manager.anticheat.PlayerDataManager;
import ru.raveon.service.train.DatasetWriter;
import ru.raveon.service.train.UploadService;
import org.bukkit.plugin.Plugin;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import sun.misc.Unsafe;

public final class DatasetVerification {
    private static DataCollectConfigManager settings;
    private static ru.raveon.service.LanguageService language;
    public static final class TestPlugin extends Raveon {
        @Override public ru.raveon.service.LanguageService getLanguageService() { return language; }
        @Override public DataCollectConfigManager getDataCollectConfigManager() { return settings; }
        @Override public Logger getLogger() { return Logger.getLogger("DatasetVerification"); }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("raveon-dataset-test");
        try (DatasetWriter writer = new DatasetWriter(2)) {
            Path file = root.resolve("rows.csv");
            writer.append(file, "header", List.of("one", "two"), value -> value).get(5, TimeUnit.SECONDS);
            writer.append(file, "header", List.of("three"), value -> value).get(5, TimeUnit.SECONDS);
            require(Files.readString(file).equals("header\none\ntwo\nthree"), "append order/header");
            String before = Files.readString(file);
            expectFailure(writer.append(file, "header", List.of("a", "bad"), value -> {
                if (value.equals("bad")) throw new IllegalArgumentException("serialization failure");
                return value;
            }));
            require(Files.readString(file).equals(before), "failed batch changed committed data");
            Path blocker = root.resolve("not-a-directory");
            Files.writeString(blocker, "block");
            expectFailure(writer.append(blocker.resolve("failed.csv"), "header", List.of("row"), value -> value));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Boolean> first = writer.append(root.resolve("bounded.csv"), "h", List.of("1"), value -> {
                entered.countDown();
                try { require(release.await(5, TimeUnit.SECONDS), "writer release deadline"); }
                catch (InterruptedException error) { throw new RuntimeException(error); }
                return value;
            });
            require(entered.await(5, TimeUnit.SECONDS), "writer did not start");
            CompletableFuture<Boolean> second = writer.append(root.resolve("bounded.csv"), "h", List.of("2"), value -> value);
            CompletableFuture<Boolean> third = writer.append(root.resolve("bounded.csv"), "h", List.of("3"), value -> value);
            expectFailure(writer.append(root.resolve("bounded.csv"), "h", List.of("4"), value -> value));
            release.countDown();
            CompletableFuture.allOf(first, second, third).get(5, TimeUnit.SECONDS);
            require(Files.readString(root.resolve("bounded.csv")).equals("h\n1\n2\n3"), "bounded queue changed order");
        }
        DatasetWriter closed = new DatasetWriter(1);
        closed.close();
        expectFailure(closed.append(root.resolve("closed.csv"), "h", List.of("x"), value -> value));
        verifySession(root);
        System.out.println("DatasetVerification passed: append, rollback, real I/O failure, queue bound, close, session retry, retained failures, retirement, owner isolation, boss bar cleanup and multi-batch shutdown");
    }

    private static void verifySession(Path root) throws Exception {
        Files.createDirectories(root.resolve("features"));
        try (InputStream source = DatasetVerification.class.getClassLoader().getResourceAsStream("features/datacollect.yml")) {
            Files.copy(source, root.resolve("features/datacollect.yml"));
        }
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDataFolder" -> root.toFile();
                    case "getLogger" -> Logger.getLogger("DatasetVerification");
                    case "getName" -> "DatasetVerification";
                    case "isEnabled" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        settings = new DataCollectConfigManager(plugin);
        language = ru.raveon.service.LanguageService.load(plugin, "en");
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        Raveon.INSTANCE = (TestPlugin) unsafe.allocateInstance(TestPlugin.class);
        UploadService.start();
        try {
            TrainData data = new TrainData(UUID.randomUUID(), "test", ModelId.PRO);
            Path blocker = root.resolve("blocked");
            Files.writeString(blocker, "file");
            UploadService failed = new UploadService(plugin, "batch", "blocked", ModelId.PRO);
            require(data.begin(failed, false, null), "initial session rejected");
            data.tagCombat();
            data.writeFrame(new RotationFrame(1, 2, 3, 4, 5, 6, 7, 8));
            require(!data.stopCollecting().get(5, TimeUnit.SECONDS), "I/O failure was reported as success");
            require(data.hasPendingFrames(), "I/O failure discarded frames");
            data.setLastCombatTime(0);
            require(!data.isInCombat(), "expired combat was retained");
            require(data.hasPendingFrames(), "combat timeout discarded failed dataset frames");
            require(!data.begin(new UploadService(plugin, "other", "datasets", ModelId.PRO), true, null), "new session discarded unsaved frames");
            Files.delete(blocker);
            Files.createDirectory(blocker);
            require(data.stopCollecting().get(5, TimeUnit.SECONDS), "retry did not save retained frames");
            require(!data.hasPendingFrames(), "committed frames retained");
            require(Files.readAllLines(blocker.resolve("batch.csv")).size() == 2, "retry lost or duplicated frame");
            require(data.begin(new UploadService(plugin, "next", "datasets", ModelId.PRO), true, null), "new session rejected after commit");
            require(data.getDatasetsUploaded() == 0 && data.getTotalFramesCollected() == 0, "new session inherited counters");
            data.tagCombat();
            data.writeFrame(new RotationFrame(8, 7, 6, 5, 4, 3, 2, 1));
            require(data.stopCollecting().get(5, TimeUnit.SECONDS), "new session flush failed");
            require(Files.readAllLines(root.resolve("datasets/next.csv")).get(1).startsWith("1,"), "session label mixed");
            require(data.retireIfSaved(), "saved session could not retire");
            require(!data.begin(new UploadService(plugin, "retired", "datasets", ModelId.PRO), false, null),
                    "retired session allowed a detached new collection");
            verifyCombatExpiry();
            verifyOwnerAndBossBar(plugin);
            verifyMultiBatchShutdown(root, plugin);
        } finally {
            UploadService.shutdown();
        }
    }

    private static void verifyCombatExpiry() throws Exception {
        Field clear = DataCollectConfigManager.class.getDeclaredField("clearBufferOnCombatTimeout");
        clear.setAccessible(true);
        boolean original = clear.getBoolean(settings);
        try {
            for (ModelId model : ModelId.values()) {
                clear.setBoolean(settings, true);
                TrainData data = new TrainData(UUID.randomUUID(), "combat", model);
                data.setDatasetsCollecting(true);
                List<?> frames = model == ModelId.PRO ? data.getDatasetFrames() : data.getUltraFrames();
                if (model == ModelId.PRO)
                    data.getDatasetFrames().add(new RotationFrame(1, 2, 3, 4, 5, 6, 7, 8));
                else data.getUltraFrames().add(new UltraFrame(1, 2, 3, 4, 5, 6, 7, 8, null));
                data.tagCombat();
                data.setLastCombatTime(System.currentTimeMillis() - 1);
                data.tagCombat();
                require(frames.isEmpty(), "Combat restart kept expired dataset frames for " + model);
                if (model == ModelId.PRO) {
                    data.getDatasetFrames().add(new RotationFrame(1, 2, 3, 4, 5, 6, 7, 8));
                    data.getDatasetFrames().add(new RotationFrame(2, 2, 3, 4, 5, 6, 7, 8));
                } else {
                    data.getUltraFrames().add(new UltraFrame(1, 2, 3, 4, 5, 6, 7, 8, null));
                    data.getUltraFrames().add(new UltraFrame(2, 2, 3, 4, 5, 6, 7, 8, null));
                }
                data.setSaving(true);
                data.setSavingFrames(1);
                data.setLastCombatTime(System.currentTimeMillis() - 1);
                data.tagCombat();
                require(frames.size() == 1, "Combat expiry dropped in-flight frames or retained pending suffix");
                data.setSaving(false);
                data.setSavingFrames(0);
                clear.setBoolean(settings, false);
                data.setLastCombatTime(System.currentTimeMillis() - 1);
                data.tagCombat();
                require(frames.size() == 1, "Disabled combat cleanup still discarded frames");
            }
        } finally {
            clear.setBoolean(settings, original);
        }
    }

    private static void verifyOwnerAndBossBar(Plugin plugin) throws Exception {
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        Plugin disabled = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (object, method, arguments) -> method.getName().equals("isEnabled") ? false : method.invoke(plugin, arguments));
        TrainData stoppedPlugin = new TrainData(UUID.randomUUID(), "disabled", ModelId.PRO);
        require(!stoppedPlugin.begin(new UploadService(disabled, "disabled", "datasets", ModelId.PRO), false, firstOwner),
                "disabled plugin admitted a new dataset session");
        TrainData data = new TrainData(UUID.randomUUID(), "owner", ModelId.PRO);
        UploadService service = new UploadService(plugin, "owner", "datasets", ModelId.PRO);
        require(data.begin(service, false, firstOwner), "owner session rejected");
        require(!data.begin(service, true, secondOwner), "concurrent second start accepted");
        require(firstOwner.equals(data.getDatasetsOwner()), "rejected start changed the current owner");
        require(!data.isMarkedCheater(), "rejected start changed the current label");
        require(data.stopCollecting().get(5, TimeUnit.SECONDS), "owner session stop failed");
        require(data.begin(service, true, secondOwner), "new completed-session owner rejected");
        require(secondOwner.equals(data.getDatasetsOwner()), "new session owner was not applied");
        java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        org.bukkit.boss.BossBar bar = (org.bukkit.boss.BossBar) Proxy.newProxyInstance(
                org.bukkit.boss.BossBar.class.getClassLoader(), new Class<?>[] {org.bukkit.boss.BossBar.class},
                (object, method, arguments) -> switch (method.getName()) {
                    case "setVisible" -> { visible.set((Boolean) arguments[0]); yield null; }
                    case "removeAll" -> { removed.incrementAndGet(); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        data.setTrainBar(bar);
        require(data.stopCollecting().get(5, TimeUnit.SECONDS), "disabled-plugin bar stop failed");
        require(!visible.get(), "disabled-plugin cleanup left the boss bar visible");

        Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object oldServer = serverField.get(null);
        Field enabledField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("isEnabled");
        enabledField.setAccessible(true);
        Object oldEnabled = enabledField.get(Raveon.INSTANCE);
        java.util.ArrayList<Runnable> scheduled = new java.util.ArrayList<>();
        org.bukkit.entity.Player owner = (org.bukkit.entity.Player) Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(), new Class<?>[] {org.bukkit.entity.Player.class},
                (object, method, arguments) -> method.getName().equals("getUniqueId") ? secondOwner : null);
        org.bukkit.scheduler.BukkitTask task = (org.bukkit.scheduler.BukkitTask) Proxy.newProxyInstance(
                org.bukkit.scheduler.BukkitTask.class.getClassLoader(), new Class<?>[] {org.bukkit.scheduler.BukkitTask.class},
                (object, method, arguments) -> null);
        org.bukkit.scheduler.BukkitScheduler scheduler = (org.bukkit.scheduler.BukkitScheduler) Proxy.newProxyInstance(
                org.bukkit.scheduler.BukkitScheduler.class.getClassLoader(), new Class<?>[] {org.bukkit.scheduler.BukkitScheduler.class},
                (object, method, arguments) -> {
                    if (!method.getName().equals("runTask")) throw new UnsupportedOperationException(method.getName());
                    scheduled.add((Runnable) arguments[1]);
                    return task;
                });
        org.bukkit.Server server = (org.bukkit.Server) Proxy.newProxyInstance(
                org.bukkit.Server.class.getClassLoader(), new Class<?>[] {org.bukkit.Server.class},
                (object, method, arguments) -> switch (method.getName()) {
                    case "getPlayer" -> secondOwner.equals(arguments[0]) ? owner : null;
                    case "getScheduler" -> scheduler;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        try {
            serverField.set(null, server);
            enabledField.set(Raveon.INSTANCE, true);
            require(data.begin(service, false, secondOwner), "enabled bar session rejected");
            visible.set(true);
            data.setTrainBar(bar);
            require(data.stopCollecting().get(5, TimeUnit.SECONDS), "enabled bar stop failed");
            require(!visible.get(), "boss bar remained visible until the owner cleanup task");
            require(!scheduled.isEmpty() && removed.get() == 0, "owner cleanup ran inline");
            scheduled.clear();
            enabledField.set(Raveon.INSTANCE, false);
            require(data.stopCollecting().get(5, TimeUnit.SECONDS), "cancelled cleanup stop failed");
            require(!visible.get(), "cancelled owner cleanup left a visible boss bar");
        } finally {
            enabledField.set(Raveon.INSTANCE, oldEnabled);
            serverField.set(null, oldServer);
        }
    }

    private static void verifyMultiBatchShutdown(Path root, Plugin plugin) throws Exception {
        Field field = UploadService.class.getDeclaredField("writer");
        field.setAccessible(true);
        DatasetWriter writer = (DatasetWriter) field.get(null);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        writer.append(root.resolve("shutdown-gate.csv"), "gate", List.of("first"), value -> {
            firstEntered.countDown();
            await(releaseFirst);
            return value;
        });
        require(firstEntered.await(5, TimeUnit.SECONDS), "shutdown gate did not start");
        PlayerDataManager players = new PlayerDataManager();
        UUID uuid = UUID.randomUUID();
        TrainData pro = players.getOrCreateTrainData(uuid, "shutdown", ModelId.PRO);
        TrainData ultra = players.getOrCreateTrainData(uuid, "shutdown", ModelId.ULTRA);
        require(pro.begin(new UploadService(plugin, "shutdown-pro", "datasets", ModelId.PRO), false, null), "Pro begin failed");
        require(ultra.begin(new UploadService(plugin, "shutdown-ultra", "datasets", ModelId.ULTRA), true, null), "Ultra begin failed");
        pro.tagCombat();
        ultra.tagCombat();
        int proFrames = settings.getFramesToCollect(ModelId.PRO) * 2;
        int ultraFrames = settings.getFramesToCollect(ModelId.ULTRA) * 2;
        for (int i = 0; i < proFrames; i++) pro.writeFrame(new RotationFrame(i, 2, 3, 4, 5, 6, 7, 8));
        for (int i = 0; i < ultraFrames; i++) ultra.writeUltraFrame(new UltraFrame(i, 2, 3, 4, 5, 6, 7, 8, null));
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        writer.append(root.resolve("shutdown-gate.csv"), "gate", List.of("second"), value -> {
            secondEntered.countDown();
            await(releaseSecond);
            return value;
        });
        CompletableFuture<Void> stopped = players.stopAllDatasets();
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
            stopped.join();
            UploadService.shutdown();
        });
        try {
            require(!stopped.isDone(), "shutdown completed before queued batches");
            releaseFirst.countDown();
            require(secondEntered.await(5, TimeUnit.SECONDS), "second batch gate did not start");
            require(pro.getDatasetsUploaded() == 1 && ultra.getDatasetsUploaded() == 1,
                    "first model batches were not committed");
            require(!stopped.isDone(), "shutdown completed while later model batches remained");
            releaseSecond.countDown();
            shutdown.get(5, TimeUnit.SECONDS);
            require(!pro.hasPendingFrames() && !ultra.hasPendingFrames(), "shutdown left unsaved frames");
            require(pro.getDatasetsUploaded() == 2 && ultra.getDatasetsUploaded() == 2,
                    "shutdown did not drain both model batches");
            List<String> proRows = Files.readAllLines(root.resolve("datasets/shutdown-pro.csv"));
            List<String> ultraRows = Files.readAllLines(root.resolve("datasets/shutdown-ultra.csv"));
            require(proRows.size() == proFrames + 1 && ultraRows.size() == ultraFrames + 1,
                    "shutdown lost or duplicated CSV rows");
            for (int i = 0; i < proFrames; i++)
                require(proRows.get(i + 1).equals(new RotationFrame(i, 2, 3, 4, 5, 6, 7, 8).toCsvRow(false)),
                        "shutdown changed Pro frame order");
            for (int i = 0; i < ultraFrames; i++)
                require(ultraRows.get(i + 1).equals(new UltraFrame(i, 2, 3, 4, 5, 6, 7, 8, null).toCsvRow(true)),
                        "shutdown changed Ultra frame order");
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(5, TimeUnit.SECONDS), "dataset gate deadline exceeded");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static void expectFailure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected failed future");
        } catch (java.util.concurrent.ExecutionException expected) {
            require(expected.getCause() != null, "missing failure cause");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
