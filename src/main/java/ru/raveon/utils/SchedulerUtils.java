package ru.raveon.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

public final class SchedulerUtils {

    private SchedulerUtils() {
    }

    public static void run(Plugin plugin, Runnable task) {
        if (!hasMethod(Bukkit.getServer().getClass(), "getGlobalRegionScheduler")) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        invokeScheduler(Bukkit.getServer(), "getGlobalRegionScheduler", "run", plugin, task);
    }

    public static void runEntity(Plugin plugin, Entity entity, Runnable task) {
        if (!hasMethod(Bukkit.getServer().getClass(), "getGlobalRegionScheduler") || entity == null) {
            run(plugin, task);
            return;
        }
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            Method method = findMethod(scheduler.getClass(), "run", 3);
            method.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run(), (Runnable) () -> { });
        } catch (ReflectiveOperationException | RuntimeException exception) {
            run(plugin, task);
        }
    }

    public static void runLater(Plugin plugin, Runnable task, long delayTicks) {
        if (!hasMethod(Bukkit.getServer().getClass(), "getGlobalRegionScheduler")) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return;
        }
        invokeScheduler(Bukkit.getServer(), "getGlobalRegionScheduler", "runDelayed", plugin, task, delayTicks);
    }

    public static TaskHandle runTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (!hasMethod(Bukkit.getServer().getClass(), "getGlobalRegionScheduler")) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return bukkitTask::cancel;
        }
        Object scheduled = invokeScheduler(Bukkit.getServer(), "getGlobalRegionScheduler", "runAtFixedRate", plugin, task, delayTicks, periodTicks);
        return () -> cancel(scheduled);
    }

    public static void runAsync(Plugin plugin, Runnable task) {
        if (!hasMethod(Bukkit.getServer().getClass(), "getGlobalRegionScheduler")) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            return;
        }
        try {
            Object scheduler = Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
            Method method = findMethod(scheduler.getClass(), "runNow", 2);
            method.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            task.run();
        }
    }

    public static TaskHandle runTimerAsync(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (!hasMethod(Bukkit.getServer().getClass(), "getGlobalRegionScheduler")) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
            return bukkitTask::cancel;
        }
        try {
            Object scheduler = Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
            Method method = findMethod(scheduler.getClass(), "runAtFixedRate", 5);
            Object scheduled = method.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run(),
                    Math.max(0L, delayTicks) * 50L, Math.max(1L, periodTicks) * 50L,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return () -> cancel(scheduled);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return () -> { };
        }
    }

    public interface TaskHandle {
        void cancel();
    }

    private static Object invokeScheduler(Object server, String getter, String methodName, Plugin plugin, Runnable task, Object... args) {
        try {
            Object scheduler = server.getClass().getMethod(getter).invoke(server);
            Method method = findMethod(scheduler.getClass(), methodName, args.length + 2);
            Object[] invocation = new Object[args.length + 2];
            invocation[0] = plugin;
            invocation[1] = (Consumer<Object>) ignored -> task.run();
            System.arraycopy(args, 0, invocation, 2, args.length);
            return method.invoke(scheduler, invocation);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            task.run();
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new IllegalStateException("Scheduler method not found: " + name);
    }

    private static void cancel(Object scheduled) {
        if (scheduled == null) return;
        try {
            scheduled.getClass().getMethod("cancel").invoke(scheduled);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean hasMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }
}
