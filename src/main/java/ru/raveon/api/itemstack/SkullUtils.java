package ru.raveon.api.itemstack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import lombok.experimental.UtilityClass;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import ru.raveon.utils.VersionHelper;

import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Level;

@UtilityClass
@SuppressWarnings("ALL")
public class SkullUtils {

    private static final Gson GSON = new Gson();
    private static final UUID skullUUID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");

    /**
     * Get the skull from a base64 encoded texture url
     *
     * @param base64Url base64 encoded url to use
     * @return skull
     */
    @NotNull
    public static ItemStack getSkullByBase64EncodedTextureUrl(@NotNull final String base64Url) {
        try {
            return createTexturedSkull(base64Url);
        } catch (RuntimeException error) {
            Bukkit.getLogger().warning("Invalid menu head texture: " + error.getClass().getSimpleName());
            return ItemStackServices.skullHead();
        }
    }

    private static ItemStack createTexturedSkull(String base64Url) {
        final ItemStack head = ItemStackServices.skullHead();
        if (base64Url.isEmpty()) {
            return head;
        }

        final SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        if (headMeta == null) {
            return head;
        }

        if (VersionHelper.HAS_PLAYER_PROFILES) {
            final PlayerProfile profile = getPlayerProfile(base64Url);
            headMeta.setOwnerProfile(profile);
            head.setItemMeta(headMeta);
            return head;
        }

        final GameProfile profile = getGameProfile(base64Url);
        final Field profileField;
        try {
            profileField = headMeta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(headMeta, profile);
        } catch (final NoSuchFieldException
                | IllegalArgumentException
                | IllegalAccessException exception) {
            Bukkit.getLogger()
                    .log(
                            Level.SEVERE,
                            "Failed to get head item from base64 texture url",
                            exception);
        }
        head.setItemMeta(headMeta);
        return head;
    }

    /**
     * Create a game profile object
     *
     * @param base64Url the base64 encoded texture url to use
     * @return game profile
     */
    @NotNull
    private static GameProfile getGameProfile(@NotNull final String base64Url) {
        GameProfile profile = new GameProfile(skullUUID, "");
        profile.getProperties().put("textures", new Property("textures", base64Url));
        return profile;
    }

    /**
     * Create a player profile object Player profile was introduced in 1.18.1+
     *
     * @param base64Url the base64 encoded texture URL to use
     * @return player profile
     */
    @NotNull
    private static PlayerProfile getPlayerProfile(@NotNull final String base64Url) {
        final PlayerProfile profile = Bukkit.createPlayerProfile(skullUUID);

        final String decodedBase64 = decodeSkinUrl(base64Url);
        if (decodedBase64 == null) {
            return profile;
        }

        final PlayerTextures textures = profile.getTextures();

        try {
            textures.setSkin(new URL(decodedBase64));
        } catch (final MalformedURLException exception) {
            Bukkit.getLogger()
                    .log(
                            Level.SEVERE,
                            "Something went horribly wrong trying to create basehead URL",
                            exception);
        }

        profile.setTextures(textures);
        return profile;
    }

    /**
     * Decode a base64 string and extract the url of the skin. Example: <br>
     * - Base64: {@code
     * eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGNlYjE3MDhkNTQwNGVmMzI2MTAzZTdiNjA1NTljOTE3OGYzZGNlNzI5MDA3YWM5YTBiNDk4YmRlYmU0NjEwNyJ9fX0=}
     * <br>
     * - JSON: {@code
     * {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/dceb1708d5404ef326103e7b60559c9178f3dce729007ac9a0b498bdebe46107"}}}}
     * <br>
     * - Result: {@code
     * http://textures.minecraft.net/texture/dceb1708d5404ef326103e7b60559c9178f3dce729007ac9a0b498bdebe46107}
     * <br>
     * Credit: <a
     * href="https://github.com/TriumphTeam/triumph-gui/pull/104/files#diff-ef6f3ffdac8e5f722e2e9121be8003b26d087c2d7871ca43d31b65c7565b0c1fR92">iGabyTM</a>
     *
     * @param base64Texture the texture
     * @return the url of the texture if found, otherwise {@code null}
     */
    @Nullable
    public static String decodeSkinUrl(@NotNull final String base64Texture) {
        final String decoded = new String(Base64.getDecoder().decode(base64Texture));
        final JsonObject object = GSON.fromJson(decoded, JsonObject.class);

        final JsonElement textures = object.get("textures");

        if (textures == null) {
            return null;
        }

        final JsonElement skin = textures.getAsJsonObject().get("SKIN");

        if (skin == null) {
            return null;
        }

        final JsonElement url = skin.getAsJsonObject().get("url");
        return url == null ? null : url.getAsString();
    }
}
