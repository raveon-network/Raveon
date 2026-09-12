package ru.raveon.manager;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.EventManager;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import lombok.Getter;
import org.bukkit.plugin.Plugin;
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
                checkManagerListener,
                combatListener,
                packetPlayerJoinQuit,
                hologramListener
        );
    }

    public void unregister() {
        EventManager events = PacketEvents.getAPI().getEventManager();
        events.unregisterListeners(
                checkManagerListener,
                combatListener,
                packetPlayerJoinQuit,
                hologramListener
        );
    }

    public void initPacketEvents(Plugin plugin) {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
        PacketEvents.getAPI().getSettings()
                .checkForUpdates(false)
                .reEncodeByDefault(false);

        PacketEvents.getAPI().load();
        register();
        PacketEvents.getAPI().init();
    }

    public void terminate() {
        if (PacketEvents.getAPI().isLoaded()) {
            PacketEvents.getAPI().terminate();
        }
    }
}
