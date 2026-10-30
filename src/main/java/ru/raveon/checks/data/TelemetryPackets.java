package ru.raveon.checks.data;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.*;

import static ru.raveon.checks.data.TelemetryUpdate.Kind.*;

public final class TelemetryPackets {
    private TelemetryPackets() {}

    public static TelemetryUpdate receive(PacketReceiveEvent event) {
        var type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_INPUT) {
            var p = new WrapperPlayClientPlayerInput(event);
            return new TelemetryUpdate(INPUT, new double[] {
                    bit(p.isForward()), bit(p.isBackward()), bit(p.isLeft()), bit(p.isRight()),
                    bit(p.isJump()), bit(p.isShift()), bit(p.isSprint())});
        }
        if (type == PacketType.Play.Client.ANIMATION) return new TelemetryUpdate(SWING, new double[0]);
        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE)
            return new TelemetryUpdate(SLOT, new double[] {new WrapperPlayClientHeldItemChange(event).getSlot()});
        if (type == PacketType.Play.Client.USE_ITEM)
            return new TelemetryUpdate(USE, new double[] {
                    bit(new WrapperPlayClientUseItem(event).getHand() == InteractionHand.OFF_HAND)});
        if (type == PacketType.Play.Client.PLAYER_DIGGING
                && new WrapperPlayClientPlayerDigging(event).getAction().name().equals("RELEASE_USE_ITEM"))
            return new TelemetryUpdate(RELEASE, new double[0]);
        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            var p = new WrapperPlayClientPlayerBlockPlacement(event);
            var pos = p.getBlockPosition();
            var cursor = p.getCursorPosition();
            return new TelemetryUpdate(PLACE, new double[] {
                    pos.getX(), pos.getY(), pos.getZ(), p.getFaceId(),
                    cursor.getX(), cursor.getY(), cursor.getZ(),
                    p.getInsideBlock().map(value -> value ? 1.0 : 0.0).orElse(-1.0),
                    bit(p.getHand() == InteractionHand.OFF_HAND)});
        }
        return null;
    }

    public static TelemetryUpdate send(PacketSendEvent event) {
        var type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            var p = new WrapperPlayServerEntityVelocity(event);
            if (p.getEntityId() != event.getUser().getEntityId()) return null;
            var v = p.getVelocity();
            return new TelemetryUpdate(VELOCITY, new double[] {v.x, v.y, v.z});
        }
        if (type == PacketType.Play.Server.EXPLOSION) {
            var v = new WrapperPlayServerExplosion(event).getKnockback();
            return new TelemetryUpdate(EXPLOSION, v == null ? new double[3] : new double[] {v.x, v.y, v.z});
        }
        if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)
            return new TelemetryUpdate(TELEPORT, new double[0]);
        return null;
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
