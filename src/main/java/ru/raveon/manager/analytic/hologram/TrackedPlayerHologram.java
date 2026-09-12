package ru.raveon.manager.analytic.hologram;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class TrackedPlayerHologram {
    private final int baseEntityId;
    private final List<PacketHologramLine> lines = new ArrayList<>();
    private final Map<UUID, Integer> renderedLineCountByViewer = new ConcurrentHashMap<>();

    public TrackedPlayerHologram() {
        this.baseEntityId = ThreadLocalRandom.current().nextInt(1_000_000, 1_900_000);
    }

    public void updateForViewer(Player viewer, Location baseLocation, List<String> textLines, double lineSpacing) {
        ensureLineCapacity(textLines.size());

        UUID viewerId = viewer.getUniqueId();
        int previousLineCount = renderedLineCountByViewer.getOrDefault(viewerId, 0);
        int newLineCount = textLines.size();

        int sharedLineCount = Math.min(previousLineCount, newLineCount);

        for (int index = 0; index < sharedLineCount; index++) {
            lines.get(index).updateText(viewer, textLines.get(index));
        }

        for (int index = previousLineCount; index < newLineCount; index++) {
            lines.get(index).spawn(viewer, lineLocation(baseLocation, lineSpacing, index), textLines.get(index));
        }

        for (int index = newLineCount; index < previousLineCount; index++) {
            lines.get(index).destroy(viewer);
        }

        renderedLineCountByViewer.put(viewerId, newLineCount);
    }

    public void teleportForViewer(Player viewer, Location baseLocation, double lineSpacing) {
        int visibleLines = renderedLineCountByViewer.getOrDefault(viewer.getUniqueId(), 0);

        for (int index = 0; index < visibleLines; index++) {
            lines.get(index).teleport(viewer, lineLocation(baseLocation, lineSpacing, index));
        }
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

    private Location lineLocation(Location baseLocation, double lineSpacing, int index) {
        return baseLocation.clone().subtract(0.0D, index * lineSpacing, 0.0D);
    }

    private void ensureLineCapacity(int requiredSize) {
        while (lines.size() < requiredSize) {
            lines.add(new PacketHologramLine(baseEntityId + lines.size()));
        }
    }
}