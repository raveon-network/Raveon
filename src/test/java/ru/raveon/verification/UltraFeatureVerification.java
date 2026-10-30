package ru.raveon.verification;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;

import ru.raveon.api.models.UltraFrame;
import ru.raveon.checks.data.UltraState;
import ru.raveon.utils.entity.EntitySnapshot;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class UltraFeatureVerification {
    private static final String EXPECTED = "3fc43cfe9a04f1a6887b4146da0c382a2244f2edb9fe15a2a78d6d020ba437a2";

    public static void main(String[] args) throws Exception {
        Map<String, Object> worldValues = new HashMap<>();
        worldValues.put("getUID", UUID.fromString("00000000-0000-0000-0000-000000000010"));
        World world = proxy(World.class, worldValues);
        Map<String, Object> otherValues = new HashMap<>();
        otherValues.put("getUID", UUID.fromString("00000000-0000-0000-0000-000000000011"));
        World other = proxy(World.class, otherValues);
        Map<String, Object> inventory = new HashMap<>();
        inventory.put("getItemInMainHand", new ItemStack(Material.DIAMOND_SWORD));
        Map<String, Object> playerValues = new HashMap<>();
        playerValues.put("getWorld", world);
        playerValues.put("getLocation", new Location(world, 12.5, 64, -8.25));
        playerValues.put("getInventory", proxy(PlayerInventory.class, inventory));
        playerValues.put("getUniqueId", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Map<String, Object> targetValues = new HashMap<>();
        targetValues.put("getWorld", world);
        targetValues.put("getLocation", new Location(world, 14.3, 65, -5.4));
        targetValues.put("getBoundingBox", new BoundingBox(14, 65, -5.7, 14.6, 66.8, -5.1));
        targetValues.put("getUniqueId", UUID.fromString("00000000-0000-0000-0000-000000000002"));
        targetValues.put("getEntityId", 2);
        targetValues.put("getGameMode", GameMode.SURVIVAL);
        Player target = proxy(Player.class, targetValues);
        Map<String, Object> spectatorValues = new HashMap<>(targetValues);
        spectatorValues.put("getGameMode", GameMode.SPECTATOR);
        spectatorValues.put("isValid", true);
        playerValues.put("getNearbyEntities",
                List.of(target, proxy(Player.class, spectatorValues), proxy(Entity.class, targetValues)));
        Player player = proxy(Player.class, playerValues);
        UltraState current = new UltraState();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Random random = new Random(0x524156454F4EL);
        for (int i = 0; i < 4096; i++) {
            float yaw = i < 512 ? i * 0.15F : (random.nextFloat() - 0.5F) * 200000;
            float pitch = i < 512 ? i % 100 * 0.15F : (random.nextFloat() - 0.5F) * 300;
            boolean rotation = i % 11 != 0;
            current.onFlying(rotation);
            if (i % 257 == 0) {
                current.resetAimState();
            }
            inventory.put("getItemInMainHand",
                    i % 17 == 0 ? null : new ItemStack(i % 3 == 0 ? Material.BOW : Material.DIAMOND_SWORD));
            targetValues.put("isDead", i % 19 == 0);
            targetValues.put("isValid", i % 23 != 0);
            targetValues.put("getWorld", i % 29 == 0 ? other : world);
            Entity entity = i % 7 == 0 ? null : target;
            EntitySnapshot snapshot = entity == null ? null : EntitySnapshot.capture(entity);
            int nearby = target.isDead() ? 0 : 1;
            UltraFrame frame = current.capture(player, snapshot, nearby, yaw, pitch);
            float[] actual = java.util.Arrays.copyOf(frame.datasetFeatures(), 24);
            ByteBuffer bytes = ByteBuffer.allocate(actual.length * 4);
            for (float value : actual) bytes.putInt(Float.floatToRawIntBits(value));
            digest.update(bytes.array());
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!EXPECTED.equals(actual))
            throw new AssertionError("Ultra golden feature mismatch: " + actual);
        System.out.println("Ultra feature verification passed: " + actual);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
            if (method.getName().equals("equals")) return object == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            if (method.getName().equals("toString")) return type.getSimpleName();
            if (values.containsKey(method.getName())) return values.get(method.getName());
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
}
