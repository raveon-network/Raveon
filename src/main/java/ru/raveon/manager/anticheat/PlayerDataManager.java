package ru.raveon.manager.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.player.User;

import lombok.Getter;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import ru.raveon.Raveon;
import ru.raveon.api.models.data.TrainData;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.reflections.GeyserUtil;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {
    @Getter private final Collection<User> exemptUsers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, User> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RaveonPlayer> playerDataMap = new ConcurrentHashMap<>();
    private final Map<ModelId, ConcurrentHashMap<UUID, TrainData>> trainDataByModel =
            createTrainMaps();

    private static Map<ModelId, ConcurrentHashMap<UUID, TrainData>> createTrainMaps() {
        var maps = new EnumMap<ModelId, ConcurrentHashMap<UUID, TrainData>>(ModelId.class);
        for (var model : ModelId.values()) maps.put(model, new ConcurrentHashMap<>());
        return maps;
    }

    public TrainData getOrCreateTrainData(UUID uuid, String name, ModelId model) {
        return trainDataByModel
                .get(model)
                .computeIfAbsent(uuid, key -> new TrainData(uuid, name, model));
    }

    @Nullable
    public RaveonPlayer getPlayer(@NotNull UUID uuid) {
        return playerDataMap.get(uuid);
    }

    public void loadOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            addUser(user);
        }
    }

    @Nullable
    public RaveonPlayer getPlayer(@NotNull User user) {
        if (user == null || user.getUUID() == null) {
            return null;
        }
        RaveonPlayer player = playerDataMap.get(user.getUUID());
        return player != null && player.getUser() == user ? player : null;
    }

    public TrainData getOrCreateTrainData(UUID uuid, String name) {
        return getOrCreateTrainData(uuid, name, ModelId.PRO);
    }

    public boolean exemptCheck(@NotNull User user) {
        if (exemptUsers.contains(user)) {
            return true;
        }

        if (!ChannelHelper.isOpen(user.getChannel())) {
            return true;
        }

        if (GeyserUtil.isBedrockPlayer(user.getUUID())) {
            exemptUsers.add(user);
            return true;
        }

        if (user.getUUID().toString().startsWith("00000000-0000-0000-0009")) {
            exemptUsers.add(user);
            return true;
        }

        return false;
    }

    public void addUser(@NotNull User user) {
        if (user == null || user.getUUID() == null || !ChannelHelper.isOpen(user.getChannel())) return;
        connections.compute(user.getUUID(), (uuid, previous) -> {
            if (previous == user) return previous;
            RaveonPlayer player = exemptCheck(user) ? null : new RaveonPlayer(user);
            if (previous != null) {
                exemptUsers.remove(previous);
                clearSession(uuid, remove(previous));
            }
            if (player != null) {
                playerDataMap.put(uuid, player);
                Raveon.INSTANCE.getPlayerOnlineService().heartbeat(uuid, user.getName());
                Raveon.INSTANCE.getViolationManager().getProbabilityStorage()
                        .ensurePlayer(uuid, user.getName()).exceptionally(error -> null);
            }
            return user;
        });
    }

    public RaveonPlayer remove(final @NotNull User user) {
        if (user == null || user.getUUID() == null) return null;
        RaveonPlayer player = getPlayer(user);
        return player != null && playerDataMap.remove(user.getUUID(), player) ? player : null;
    }

    public void onDisconnect(User user) {
        if (user == null || user.getUUID() == null) return;
        exemptUsers.remove(user);
        connections.computeIfPresent(user.getUUID(), (uuid, current) -> {
            if (current != user) return current;
            clearSession(uuid, remove(user));
            return null;
        });
    }

    private void clearSession(UUID uuid, RaveonPlayer player) {
        if (player != null) player.getCheckManager().close();
        Raveon.INSTANCE.getPlayerOnlineService().quit(uuid);
        Raveon.INSTANCE.getAlertManager().clearSession(uuid);
        Raveon.INSTANCE.getViolationManager().removePlayerData(uuid);
        trainDataByModel.values().forEach(sessions -> finishTrainData(sessions, uuid));
    }

    private void finishTrainData(ConcurrentHashMap<UUID, TrainData> sessions, UUID uuid) {
        TrainData trainData = sessions.get(uuid);
        if (trainData == null) {
            return;
        }
        trainData.stopCollecting().whenComplete((saved, error) -> {
            if (error == null && Boolean.TRUE.equals(saved)
                    && trainData.retireIfSaved()) {
                sessions.remove(uuid, trainData);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<Void> stopAllDatasets() {
        var saves = trainDataByModel.values().stream()
                .flatMap(sessions -> sessions.values().stream())
                .map(TrainData::stopCollecting).toList();
        return java.util.concurrent.CompletableFuture.allOf(
                saves.toArray(java.util.concurrent.CompletableFuture[]::new)).thenRun(() -> {
            if (saves.stream().anyMatch(saved -> !Boolean.TRUE.equals(saved.join())))
                throw new IllegalStateException("Some dataset frames could not be saved");
        });
    }

    public Collection<RaveonPlayer> getEntries() {
        return playerDataMap.values();
    }

}
