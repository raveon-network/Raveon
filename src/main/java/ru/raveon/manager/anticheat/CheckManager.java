package ru.raveon.manager.anticheat;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableClassToInstanceMap;
import lombok.Getter;
import ru.raveon.api.models.AbstractCheck;
import ru.raveon.checks.data.RotationData;
import ru.raveon.checks.impl.ai.AimAI;
import ru.raveon.checks.type.PacketCheck;
import ru.raveon.player.RaveonPlayer;

import java.util.ArrayList;
import java.util.List;

@Getter
public class CheckManager {
    private final RotationData rotationData;
    private final AimAI aimAI;

    private final ClassToInstanceMap<AbstractCheck> allChecks;
    private final ClassToInstanceMap<PacketCheck> packetChecks;

    private final List<PacketCheck> packetChecksValues;

    public CheckManager(RaveonPlayer player) {
        this.rotationData = new RotationData(player);
        this.aimAI = new AimAI(player);

        this.packetChecks = new ImmutableClassToInstanceMap.Builder<PacketCheck>()
                .put(RotationData.class, rotationData)
                .put(AimAI.class, aimAI)
                .build();

        this.allChecks = new ImmutableClassToInstanceMap.Builder<AbstractCheck>()
                .putAll(packetChecks)
                .build();

        this.packetChecksValues = new ArrayList<>(packetChecks.values());

        // Checks are loaded here, after their constructors finished: loading them from the
        // Check constructor would let subclass field initializers overwrite the config values.
        reload();
    }

    public void onPacketSend(PacketSendEvent packet) {
        for (PacketCheck check : packetChecksValues) {
            check.onPacketSend(packet);
        }
    }

    public void onPacketReceive(PacketReceiveEvent packet) {
        for (PacketCheck check : packetChecksValues) {
            check.onPacketReceive(packet);
        }
    }

    public void reload() {
        rotationData.reload();
        aimAI.reload();
    }
}
