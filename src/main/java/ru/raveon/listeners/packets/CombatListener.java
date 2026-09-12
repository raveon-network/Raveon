package ru.raveon.listeners.packets;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ru.raveon.Raveon;
import ru.raveon.player.RaveonPlayer;

public final class CombatListener extends PacketListenerAbstract {
    public CombatListener() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
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

        Entity target = Raveon.INSTANCE.getTargetEntityIndex().getByEntityId(interactPacket.getEntityId());
        if (target == null) {
            return;
        }

        if (!(target instanceof Player)) {
            return;
        }

        raveonPlayer.markAttack(target.getUniqueId());
    }
}
