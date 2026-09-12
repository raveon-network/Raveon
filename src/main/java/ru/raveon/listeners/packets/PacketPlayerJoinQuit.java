package ru.raveon.listeners.packets;

import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.entity.Player;
import ru.raveon.Raveon;

import java.util.UUID;

public class PacketPlayerJoinQuit extends PacketListenerAbstract {

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Login.Server.LOGIN_SUCCESS) {
            event.getTasksAfterSend().add(() -> Raveon.INSTANCE.getPlayerDataManager().addUser(event.getUser()));
        }
    }

    @Override
    public void onUserConnect(UserConnectEvent event) {
        if (event.getUser().getConnectionState() == ConnectionState.PLAY && !Raveon.INSTANCE.getPlayerDataManager().getExemptUsers().contains(event.getUser())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        Raveon.INSTANCE.getPlayerDataManager().onDisconnect(event.getUser());
    }

    @Override
    public void onUserLogin(UserLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (player.hasPermission("raveon.command.alert")
                && player.hasPermission("raveon.command.alert.enable-on-join")) {

            if (!Raveon.INSTANCE.getAlertManager().hasAlertsEnabled(uuid)) {
                Raveon.INSTANCE.getAlertManager().setAlertsEnabled(uuid, true, true);
            }
        }

        if (player.hasPermission("raveon.command.verbose")
                && player.hasPermission("raveon.command.verbose.enable-on-join")) {

            if (!Raveon.INSTANCE.getAlertManager().hasVerboseEnabled(uuid)) {
                Raveon.INSTANCE.getAlertManager().setVerboseEnabled(uuid, true, true);
            }
        }

        if (player.hasPermission("raveon.command.hologram")
                && player.hasPermission("raveon.command.hologram.enable-on-join")) {

            if (!Raveon.INSTANCE.getHologramManager().hasHologramsEnabled(uuid)) {
                Raveon.INSTANCE.getHologramManager().setHologramsEnabled(uuid, true, true);
            }
        }
    }
}