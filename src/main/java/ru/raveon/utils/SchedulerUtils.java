package ru.raveon.utils;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class SchedulerUtils {
    private static final boolean FOLIA = classPresent("io.papermc.paper.threadedregions.RegionizedServer");

    private SchedulerUtils() {}

    public static boolean isFolia() {
        return FOLIA;
    }

    public static void run(Plugin plugin, Runnable task) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        invoke(Folia.GLOBAL_RUN, globalScheduler(), plugin, (Consumer<Object>) ignored -> task.run());
    }

    public static boolean runEntity(Plugin plugin, Entity entity, Runnable task) {
        return runEntity(plugin, entity, task, () -> {});
    }

    public static boolean runEntity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        if (entity == null || !plugin.isEnabled()) {
            retired.run();
            return false;
        }
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (entity.isValid()) task.run();
                else retired.run();
            });
            return true;
        }
        Object scheduler = invoke(Folia.ENTITY_SCHEDULER, entity);
        boolean accepted = (Boolean) invoke(Folia.ENTITY_EXECUTE, scheduler, plugin, task, retired, 1L);
        if (!accepted) retired.run();
        return accepted;
    }

    public static void runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        if (!FOLIA) {
            run(plugin, task);
            return;
        }
        invoke(Folia.REGION_RUN, invoke(Folia.REGION_SCHEDULER, null),
                plugin, world, chunkX, chunkZ, (Consumer<Object>) ignored -> task.run());
    }

    public static boolean isOwned(Entity entity) {
        return !FOLIA ? Bukkit.isPrimaryThread() : (Boolean) invoke(Folia.OWNS_ENTITY, null, entity);
    }

    public static boolean isOwned(World world, int chunkX, int chunkZ) {
        return !FOLIA ? Bukkit.isPrimaryThread()
                : (Boolean) invoke(Folia.OWNS_CHUNK, null, world, chunkX, chunkZ);
    }

    public static TaskHandle runTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(
                    plugin, task, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
            return scheduled::cancel;
        }
        Object scheduled = invoke(Folia.GLOBAL_TIMER, globalScheduler(), plugin,
                (Consumer<Object>) ignored -> task.run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        return () -> invoke(Folia.CANCEL, scheduled);
    }

    public static TaskHandle runTimerAsync(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            BukkitTask scheduled = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, task, Math.max(0L, delayTicks), Math.max(1L, periodTicks));
            return scheduled::cancel;
        }
        Object scheduled = invoke(Folia.ASYNC_TIMER, invoke(Folia.ASYNC_SCHEDULER, null),
                plugin, (Consumer<Object>) ignored -> task.run(),
                Math.multiplyExact(Math.max(0L, delayTicks), 50L),
                Math.multiplyExact(Math.max(1L, periodTicks), 50L), TimeUnit.MILLISECONDS);
        return () -> invoke(Folia.CANCEL, scheduled);
    }

    public interface TaskHandle {
        void cancel();
    }

    private static Object globalScheduler() {
        return invoke(Folia.GLOBAL_SCHEDULER, null);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, SchedulerUtils.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Scheduler invocation failed: " + method.getName(), cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Scheduler invocation failed: " + method.getName(), exception);
        }
    }

    private static final class Folia {
        private static final Method GLOBAL_SCHEDULER = method(Bukkit.class, "getGlobalRegionScheduler");
        private static final Method ASYNC_SCHEDULER = method(Bukkit.class, "getAsyncScheduler");
        private static final Method REGION_SCHEDULER = method(Bukkit.class, "getRegionScheduler");
        private static final Method ENTITY_SCHEDULER = method(Entity.class, "getScheduler");
        private static final Method OWNS_ENTITY = method(Bukkit.class, "isOwnedByCurrentRegion", Entity.class);
        private static final Method OWNS_CHUNK = method(
                Bukkit.class, "isOwnedByCurrentRegion", World.class, int.class, int.class);
        private static final Method GLOBAL_RUN = method(
                GLOBAL_SCHEDULER.getReturnType(), "run", Plugin.class, Consumer.class);
        private static final Method GLOBAL_TIMER = method(
                GLOBAL_SCHEDULER.getReturnType(), "runAtFixedRate",
                Plugin.class, Consumer.class, long.class, long.class);
        private static final Method ASYNC_TIMER = method(
                ASYNC_SCHEDULER.getReturnType(), "runAtFixedRate",
                Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
        private static final Method REGION_RUN = method(
                REGION_SCHEDULER.getReturnType(), "run",
                Plugin.class, World.class, int.class, int.class, Consumer.class);
        private static final Method ENTITY_EXECUTE = method(
                ENTITY_SCHEDULER.getReturnType(), "execute",
                Plugin.class, Runnable.class, Runnable.class, long.class);
        private static final Method CANCEL = method(GLOBAL_RUN.getReturnType(), "cancel");

        private static Method method(Class<?> owner, String name, Class<?>... args) {
            try {
                return owner.getMethod(name, args);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Folia scheduler API unavailable: " + name, exception);
            }
        }
    }
}
