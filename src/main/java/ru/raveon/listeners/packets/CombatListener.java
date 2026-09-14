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
        if (event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }

        WrapperPlayClientInteractEntity interactPacket = new WrapperPlayClientInteractEntity(event);
        if (interactPacket.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        RaveonPlayer raveonPlayer = Raveon.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (raveonPlayer == null) {
            return;
        }

        java.util.UUID targetUuid = Raveon.INSTANCE.getTargetEntityIndex()
                .getPlayerUniqueIdByEntityId(interactPacket.getEntityId());
        // Target indexing is optional context. The attack itself must still
        // activate collection, otherwise one player can disappear from AI
        // requests when the entity index is temporarily stale.
        raveonPlayer.markAttack(targetUuid);
    }
}
