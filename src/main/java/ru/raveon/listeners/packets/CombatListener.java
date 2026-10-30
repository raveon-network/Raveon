package ru.raveon.listeners.packets;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

import ru.raveon.Raveon;
import ru.raveon.player.RaveonPlayer;

public final class CombatListener extends PacketListenerAbstract {
    public CombatListener() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.isCancelled() || event.getUser() == null || event.getUser().getUUID() == null
                || event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        RaveonPlayer player = Raveon.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player != null) player.getCheckManager().handleAttack(event.getPlayer(), interact.getEntityId());
    }
}
