package ru.raveon.verification;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import ru.raveon.Raveon;
import ru.raveon.utils.entity.TargetEntityIndex;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

public final class TargetIndexVerification {
    public static void main(String[] args) throws Exception {
        Field serverField = field(Bukkit.class, "server");
        Object oldServer = serverField.get(null);
        Raveon oldPlugin = Raveon.INSTANCE;
        AtomicBoolean owner = new AtomicBoolean(true);
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        Map<String, Object> schedulerValues = new HashMap<>();
        BukkitTask task = proxy(BukkitTask.class, Map.of());
        schedulerValues.put("runTask", (BiFunction<Object, Object[], Object>) (object, values) -> {
            scheduled.add((Runnable) values[1]);
            return task;
        });
        Map<String, Object> serverValues = new HashMap<>();
        serverValues.put("getScheduler", proxy(BukkitScheduler.class, schedulerValues));
        serverValues.put("isPrimaryThread", (BiFunction<Object, Object[], Object>) (object, values) -> owner.get());
        serverField.set(null, proxy(Server.class, serverValues));
        Raveon plugin = allocate(Raveon.class);
        field(plugin.getClass(), "isEnabled").set(plugin, true);
        Raveon.INSTANCE = plugin;
        try {
            UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000010");
            World world = proxy(World.class, Map.of("getUID", worldId));
            UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
            Map<String, Object> originalValues = entityValues(uuid, 7, world, owner);
            Map<String, Object> replacementValues = entityValues(uuid, 7, world, owner);
            Player original = proxy(Player.class, originalValues);
            Player replacement = proxy(Player.class, replacementValues);
            TargetEntityIndex index = new TargetEntityIndex();
            index.track(original);
            require(uuid.equals(index.getUniqueIdByEntityId(7)), "tracked target must resolve");
            owner.set(false);
            for (int i = 0; i < 100; i++) index.getSnapshot(uuid);
            require(scheduled.size() == 1, "concurrent snapshot demand must coalesce");
            owner.set(true);
            index.track(replacement);
            originalValues.put("isValid", false);
            scheduled.remove().run();
            require(uuid.equals(index.getUniqueIdByEntityId(7)), "retired old target must not remove replacement");
            for (int tick = 0; tick < 20; tick++) {
                owner.set(false);
                for (int i = 0; i < 100; i++) index.getSnapshot(uuid);
                require(scheduled.size() == 1, "one refresh must remain scheduled");
                owner.set(true);
                scheduled.remove().run();
                require(scheduled.size() == 1, "active demand must refresh again next tick");
            }
            index.untrack(original);
            require(uuid.equals(index.getUniqueIdByEntityId(7)), "old remove event must preserve replacement");
            index.untrack(replacement);
            scheduled.remove().run();
            require(scheduled.isEmpty(), "removed target must stop refresh chain");
            require(index.getUniqueIdByEntityId(7) == null, "removed entity id must be cleared");
            index.track(replacement);
            owner.set(false);
            index.getSnapshot(uuid);
            owner.set(true);
            index.clear();
            scheduled.remove().run();
            require(scheduled.isEmpty(), "clear must retire pending refresh");
            index.track(replacement);
            owner.set(false);
            index.getSnapshot(uuid);
            owner.set(true);
            Thread.sleep(300);
            scheduled.remove().run();
            require(scheduled.isEmpty(), "idle demand must stop the refresh chain");
            System.out.println("Target index lifecycle verification passed");
        } finally {
            Raveon.INSTANCE = oldPlugin;
            serverField.set(null, oldServer);
        }
    }

    private static Map<String, Object> entityValues(UUID uuid, int id, World world, AtomicBoolean owner) {
        Map<String, Object> values = new HashMap<>();
        values.put("getUniqueId", uuid);
        values.put("getEntityId", id);
        values.put("getGameMode", GameMode.SURVIVAL);
        values.put("isValid", true);
        values.put("getWorld", owned(owner, world));
        values.put("getLocation", owned(owner, new Location(world, 1, 64, 1)));
        values.put("getBoundingBox", owned(owner, new BoundingBox(.7, 64, .7, 1.3, 65.8, 1.3)));
        return values;
    }

    private static BiFunction<Object, Object[], Object> owned(AtomicBoolean owner, Object value) {
        return (object, args) -> {
            require(owner.get(), "entity spatial data read outside owner thread");
            return value;
        };
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = field(unsafeType, "theUnsafe");
        return (T) unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
            if (method.getName().equals("equals")) return object == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            if (method.getName().equals("toString")) return type.getSimpleName();
            Object value = values.get(method.getName());
            if (value instanceof BiFunction<?, ?, ?> function)
                return ((BiFunction<Object, Object[], Object>) function).apply(object, args);
            if (values.containsKey(method.getName())) return value;
            Class<?> result = method.getReturnType();
            if (!result.isPrimitive()) return null;
            if (result == boolean.class) return false;
            if (result == char.class) return '\0';
            if (result == long.class) return 0L;
            if (result == double.class) return 0D;
            if (result == float.class) return 0F;
            return result == void.class ? null : 0;
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
