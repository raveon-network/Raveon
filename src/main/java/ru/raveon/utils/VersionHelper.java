package ru.raveon.utils;

import com.google.common.primitives.Ints;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public final class VersionHelper {
    public final String PACKAGE_NAME = Bukkit.getServer().getClass().getPackage().getName();
    public final String NMS_VERSION = PACKAGE_NAME.substring(PACKAGE_NAME.lastIndexOf('.') + 1);

    public final int CURRENT_VERSION = getCurrentVersion();

    public final boolean IS_ITEM_LEGACY = (CURRENT_VERSION < 1130);
    public final boolean IS_SKULL_OWNER_LEGACY = (CURRENT_VERSION <= 1120);

    public final boolean IS_PDC_VERSION = (CURRENT_VERSION >= 1140);
    public final boolean IS_CUSTOM_MODEL_DATA = (CURRENT_VERSION >= 1140);

    public final boolean IS_HEX_VERSION = (CURRENT_VERSION >= 1160);

    public final boolean HAS_OBFUSCATED_NAMES = (CURRENT_VERSION >= 1170);
    public final boolean HAS_PLAYER_PROFILES = (CURRENT_VERSION >= 1181);

    public final boolean IS_PAPER = isPaper();
    public final boolean IS_FOLIA = isFolia();

    public final boolean IS_COMPONENT = (IS_PAPER && CURRENT_VERSION >= 1165);

    
    private List<InventoryType> CHEST_INVENTORY_TYPES = null;
    private List<InventoryType> VALID_INVENTORY_TYPES = null;
    
    private List<InventoryType> getChestInventoryTypes() {
        if (CHEST_INVENTORY_TYPES != null) {
            return CHEST_INVENTORY_TYPES;
        }

        if (CURRENT_VERSION >= 1140) {
            CHEST_INVENTORY_TYPES = List.of(InventoryType.BARREL, InventoryType.CHEST, InventoryType.CRAFTING, InventoryType.CREATIVE, InventoryType.ENDER_CHEST, InventoryType.LECTERN, InventoryType.MERCHANT, InventoryType.SHULKER_BOX);
            return CHEST_INVENTORY_TYPES;
        }

        CHEST_INVENTORY_TYPES = List.of(InventoryType.CHEST, InventoryType.CRAFTING, InventoryType.CREATIVE, InventoryType.ENDER_CHEST, InventoryType.MERCHANT, InventoryType.SHULKER_BOX);
        return CHEST_INVENTORY_TYPES;
    }

    public List<InventoryType> getValidInventoryTypes() {
        if (VALID_INVENTORY_TYPES != null) {
            return VALID_INVENTORY_TYPES;
        }

        List<InventoryType> chestInventoryTypes = getChestInventoryTypes();
        List<InventoryType> validInventoryTypes = new ArrayList<>();
        for (InventoryType inventoryType : InventoryType.values()) {
            if (inventoryType == InventoryType.CHEST || !chestInventoryTypes.contains(inventoryType)) {
                validInventoryTypes.add(inventoryType);
            }
        }

        VALID_INVENTORY_TYPES = validInventoryTypes;
        return VALID_INVENTORY_TYPES;
    }

    public boolean isPaper() {
        return classExists("com.destroystokyo.paper.PaperConfig")
                || classExists("io.papermc.paper.configuration.Configuration");
    }

    public boolean isFolia() {
        return classExists("io.papermc.paper.threadedregions.RegionizedServer");
    }

    public boolean isSpigot() {
        return classExists("org.spigotmc.SpigotConfig");
    }

    private boolean classExists(String name) {
        try {
            Class.forName(name, false, VersionHelper.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private int getCurrentVersion() {
        Matcher matcher = Pattern.compile("(?<version>\\d+\\.\\d+)(?<patch>\\.\\d+)?").matcher(Bukkit.getBukkitVersion());
        StringBuilder stringBuilder = new StringBuilder();
        if (matcher.find()) {
            stringBuilder.append(matcher.group("version").replace(".", ""));
            String patch = matcher.group("patch");
            if (patch == null) {
                stringBuilder.append("0");
            } else {
                stringBuilder.append(patch.replace(".", ""));
            }
        }

        Integer version = Ints.tryParse(stringBuilder.toString());
        if (version == null) {
            throw new RuntimeException("Could not retrieve server version!");
        }

        return version;
    }

    public String getNmsVersion() {
        String version = Bukkit.getServer().getClass().getPackage().getName();
        return version.substring(version.lastIndexOf('.') + 1);
    }

    public Class<?> getNMSClass(String pkg, String className) throws ClassNotFoundException {
        if (HAS_OBFUSCATED_NAMES) {
            return Class.forName("net.minecraft." + pkg + "." + className);
        }

        return Class.forName("net.minecraft.server." + NMS_VERSION + "." + className);
    }

    public Class<?> getCraftClass(@NotNull String name) throws ClassNotFoundException {
        return Class.forName("org.bukkit.craftbukkit." + NMS_VERSION + "." + name);
    }
}

