package ru.raveon.utils.reflections;

import lombok.experimental.UtilityClass;
import org.geysermc.api.Geyser;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

@UtilityClass
public class GeyserUtil {
    private static final boolean floodgate = ReflectionUtils.hasClass("org.geysermc.floodgate.api.FloodgateApi");
    private static final boolean geyser = ReflectionUtils.hasClass("org.geysermc.api.Geyser");

    public static boolean isBedrockPlayer(UUID uuid) {
        return floodgate && FloodgateApi.getInstance().isFloodgatePlayer(uuid)
                || geyser && Geyser.api().isBedrockPlayer(uuid);
    }
}
