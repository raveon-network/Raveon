package ru.raveon.manager;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.EventManager;

import lombok.Getter;

import ru.raveon.listeners.packets.CheckManagerListener;
import ru.raveon.listeners.packets.CombatListener;
import ru.raveon.listeners.packets.HologramListener;
import ru.raveon.listeners.packets.PacketPlayerJoinQuit;

@Getter
public class PacketManager {
    private final CheckManagerListener checkManagerListener = new CheckManagerListener();
    private final CombatListener combatListener = new CombatListener();
    private final PacketPlayerJoinQuit packetPlayerJoinQuit = new PacketPlayerJoinQuit();
    private final HologramListener hologramListener = new HologramListener();

    public void register() {
        EventManager events = PacketEvents.getAPI().getEventManager();
        events.registerListeners(
                checkManagerListener, combatListener, packetPlayerJoinQuit, hologramListener);
    }

    public void unregister() {
        EventManager events = PacketEvents.getAPI().getEventManager();
        events.unregisterListeners(
                checkManagerListener, combatListener, packetPlayerJoinQuit, hologramListener);
    }
}
