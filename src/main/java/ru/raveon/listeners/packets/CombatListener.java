package ru.raveon.listeners.packets;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ru.raveon.Raveon;
import ru.raveon.player.RaveonPlayer;

import java.util.UUID;

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

        WrapperPlayClientInteractEntity interactPacket =
                new WrapperPlayClientInteractEntity(event);

        if (interactPacket.getAction()
                != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        RaveonPlayer raveonPlayer =
                Raveon.INSTANCE.getPlayerDataManager()
                        .getPlayer(event.getUser());

        if (raveonPlayer == null) {
            return;
        }

        UUID targetUuid = Raveon.INSTANCE.getTargetEntityIndex()
                .getPlayerUniqueIdByEntityId(interactPacket.getEntityId());

        if (targetUuid == null) {
            Player bukkitPlayer = event.getPlayer();
            Entity entity = SpigotConversionUtil.getEntityById(
                    bukkitPlayer != null ? bukkitPlayer.getWorld() : null,
                    interactPacket.getEntityId()
            );
            if (entity != null && "minecraft:mannequin".equals(entity.getType().getKey().toString())) {
                targetUuid = entity.getUniqueId();
            }
        }

        if (targetUuid == null) {
            return;
        }

        raveonPlayer.markAttack(targetUuid);
    }
}