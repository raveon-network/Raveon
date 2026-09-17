package ru.raveon.manager.analytic.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

public final class TrackedPlayerHologram {
    private final int targetEntityId;
    private final List<PacketHologramLine> lines = new CopyOnWriteArrayList<>();
    private final Map<UUID, Integer> renderedLineCountByViewer = new ConcurrentHashMap<>();

    private volatile int[] targetPassengerIds = new int[0];

    public TrackedPlayerHologram(int targetEntityId) {
        this.targetEntityId = targetEntityId;
    }

    public int getTargetEntityId() {
        return targetEntityId;
    }

    public boolean isShownTo(UUID viewerId) {
        return renderedLineCountByViewer.getOrDefault(viewerId, 0) > 0;
    }

    /**
     * Remembers the target's real passengers so our SET_PASSENGERS packets don't dismount them client-side.
     */
    public void refreshTargetPassengers(Player target) {
        targetPassengerIds = target.getPassengers().stream().mapToInt(Entity::getEntityId).toArray();
    }

    public void updateForViewer(
            Player viewer,
            Location targetLocation,
            List<String> textLines,
            double offset,
            double lineSpacing
    ) {
        ensureLineCapacity(textLines.size());

        UUID viewerId = viewer.getUniqueId();
        int previousLineCount = renderedLineCountByViewer.getOrDefault(viewerId, 0);
        int newLineCount = textLines.size();
        boolean mounted = PacketHologramLine.usesDisplayEntities();

        int sharedLineCount = Math.min(previousLineCount, newLineCount);

        for (int index = 0; index < sharedLineCount; index++) {
            lines.get(index).updateText(viewer, textLines.get(index));
        }

        for (int index = previousLineCount; index < newLineCount; index++) {
            double height = lineHeight(offset, lineSpacing, index);
            Location spawnLocation = mounted
                    ? targetLocation
                    : targetLocation.clone().add(0.0D, height, 0.0D);

            lines.get(index).spawn(viewer, spawnLocation, textLines.get(index), height);
        }

        for (int index = newLineCount; index < previousLineCount; index++) {
            lines.get(index).destroy(viewer);
        }

        renderedLineCountByViewer.put(viewerId, newLineCount);

        if (mounted && previousLineCount != newLineCount) {
            mountForViewer(viewer);
        }
    }

    /**
     * Legacy (pre-1.19.4) fallback: armor stands can't be offset while riding, so they are moved manually.
     */
    public void teleportForViewer(Player viewer, Location targetLocation, double offset, double lineSpacing) {
        int visibleLines = renderedLineCountByViewer.getOrDefault(viewer.getUniqueId(), 0);

        for (int index = 0; index < visibleLines; index++) {
            double height = lineHeight(offset, lineSpacing, index);
            lines.get(index).teleport(viewer, targetLocation.clone().add(0.0D, height, 0.0D));
        }
    }

    public void mountForViewer(Player viewer) {
        if (!isShownTo(viewer.getUniqueId())) {
            return;
        }

        PacketEvents.getAPI().getPlayerManager().sendPacket(
                viewer,
                new WrapperPlayServerSetPassengers(targetEntityId, mergePassengers(viewer.getUniqueId(), targetPassengerIds))
        );
    }

    /**
     * Appends the hologram lines rendered for the viewer to a passenger list (skipping ids already present).
     */
    public int[] mergePassengers(UUID viewerId, int[] passengers) {
        int visibleLines = renderedLineCountByViewer.getOrDefault(viewerId, 0);

        int[] missingIds = IntStream.range(0, Math.min(visibleLines, lines.size()))
                .map(index -> lines.get(index).getEntityId())
                .filter(id -> IntStream.of(passengers).noneMatch(existing -> existing == id))
                .toArray();

        if (missingIds.length == 0) {
            return passengers;
        }

        return IntStream.concat(IntStream.of(passengers), IntStream.of(missingIds)).toArray();
    }

    public void destroyForViewer(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        Integer visibleLines = renderedLineCountByViewer.remove(viewerId);

        if (visibleLines == null || visibleLines <= 0) {
            return;
        }

        for (int index = 0; index < visibleLines; index++) {
            lines.get(index).destroy(viewer);
        }
    }

    private double lineHeight(double offset, double lineSpacing, int index) {
        return offset - index * lineSpacing;
    }

    private void ensureLineCapacity(int requiredSize) {
        while (lines.size() < requiredSize) {
            lines.add(new PacketHologramLine());
        }
    }
}
