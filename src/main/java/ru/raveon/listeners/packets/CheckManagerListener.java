package ru.raveon.listeners.packets;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import ru.raveon.Raveon;
import ru.raveon.player.RaveonPlayer;

public final class CheckManagerListener extends PacketListenerAbstract {
    public CheckManagerListener() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        RaveonPlayer player = Raveon.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) {
            return;
        }

        if (event.getConnectionState() == ConnectionState.PLAY
                && event.getUser() != null
                && event.getUser().getUUID() != null) {
            player.getCheckManager().onPacketReceive(event);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getConnectionState() != ConnectionState.PLAY) {
            return;
        }

        RaveonPlayer player = Raveon.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) {
            return;
        }

        player.getCheckManager().onPacketSend(event);
    }
}
