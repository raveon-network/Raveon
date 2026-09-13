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
import ru.raveon.player.RaveonPlayer;
import ru.raveon.utils.reflections.GeyserUtil;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {
    @Getter
    private final Collection<User> exemptUsers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, RaveonPlayer> playerDataMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TrainData> trainDataMap = new ConcurrentHashMap<>();

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
    public RaveonPlayer getPlayer(String username) {
        Player player = Bukkit.getPlayer(username);
        if (player == null) {
            return null;
        }

        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(player.getUniqueId());
        User user = PacketEvents.getAPI().getProtocolManager().getUser(channel);
        return getPlayer(user);
    }

    @Nullable
    public RaveonPlayer getPlayer(@NotNull User user) {
        if (user == null || user.getUUID() == null) {
            return null;
        }
        return playerDataMap.get(user.getUUID());
    }

    public TrainData getOrCreateTrainData(UUID uuid, String name) {
        return trainDataMap.computeIfAbsent(uuid, k -> new TrainData(uuid, name));
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
        if (user == null || user.getUUID() == null) {
            return;
        }
        if (exemptCheck(user)) {
            return;
        }

        Raveon.INSTANCE.getPlayerOnlineService()
                .heartbeat(
                        user.getUUID(),
                        user.getName()
                );
        Raveon.INSTANCE
                .getViolationManager()
                .getProbabilityStorage()
                .getOrCreatePlayerData(user.getUUID(), user.getName());

        RaveonPlayer player = new RaveonPlayer(user);
        playerDataMap.put(user.getUUID(), player);
    }

    public RaveonPlayer remove(final @NotNull User user) {
        if (user == null || user.getUUID() == null) {
            return null;
        }
        return playerDataMap.remove(user.getUUID());
    }

    public void onDisconnect(User user) {
        if (user == null || user.getUUID() == null) {
            return;
        }

        Raveon.INSTANCE.getPlayerOnlineService()
                .quit(user.getUUID());

        exemptUsers.remove(user);
        RaveonPlayer player = remove(user);

        if (player != null) {
            Raveon.INSTANCE.getAlertManager().setAlertsEnabled(player.getUuid(), false, true);
            Raveon.INSTANCE.getAlertManager().setVerboseEnabled(player.getUuid(), false, true);
        }
    }

    public Collection<RaveonPlayer> getEntries() {
        return playerDataMap.values();
    }

    public int size() {
        return playerDataMap.size();
    }
}
