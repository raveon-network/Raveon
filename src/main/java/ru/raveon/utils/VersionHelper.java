package ru.raveon.utils;

import com.google.common.primitives.Ints;

import lombok.experimental.UtilityClass;

import org.bukkit.Bukkit;

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

    public boolean isPaper() {
        return classExists("com.destroystokyo.paper.PaperConfig")
                || classExists("io.papermc.paper.configuration.Configuration");
    }

    public boolean isFolia() {
        return classExists("io.papermc.paper.threadedregions.RegionizedServer");
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
        Matcher matcher =
                Pattern.compile("(?<version>\\d+\\.\\d+)(?<patch>\\.\\d+)?")
                        .matcher(Bukkit.getBukkitVersion());
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

}
