package ru.raveon.listeners.packets;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import ru.raveon.Raveon;

public class PacketPlayerJoinQuit extends PacketListenerAbstract {
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Login.Server.LOGIN_SUCCESS) {
            event.getTasksAfterSend()
                    .add(() -> Raveon.INSTANCE.getPlayerDataManager().addUser(event.getUser()));
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        Raveon.INSTANCE.getPlayerDataManager().onDisconnect(event.getUser());
    }
}
