package ru.raveon.manager.analytic.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.entity.Player;

/**
 * Draws AI-probability text directly below a player's nametag using a fake, per-viewer
 * scoreboard objective sent purely via packets (no real Bukkit {@link org.bukkit.scoreboard.Scoreboard}
 * is ever touched, so this cannot clash with any real scoreboard plugin on the server side).
 * <p>
 * Only supported from Minecraft 1.20.3 onward: that is the first version whose protocol lets a
 * score entry render an arbitrary {@link Component} instead of a plain number
 * ({@code ScoreFormat.fixedScore}). On older clients this feature must not be used at all;
 * {@link #supportsBelowName(Player)} is the single source of truth for that check.
 */
public final class BelowNameBridge {
    private static final int BELOW_NAME_SLOT = 2;
    private static final int PLACEHOLDER_SCORE = 0;

    public boolean supportsBelowName(Player viewer) {
        if (!PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_20_3)) {
            return false;
        }

        ClientVersion clientVersion = PacketEvents.getAPI().getPlayerManager().getClientVersion(viewer);
        return clientVersion != null && clientVersion.isNewerThanOrEquals(ClientVersion.V_1_20_3);
    }

    /**
     * Creates the fake objective for this viewer and immediately puts it in the below-name slot.
     * Safe to call again for the same viewer (e.g. on rejoin): it just recreates the objective.
     */
    public void createObjective(Player viewer, String objectiveName) {
        WrapperPlayServerScoreboardObjective create = new WrapperPlayServerScoreboardObjective(
                objectiveName,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
                Component.empty(),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                ScoreFormat.blankScore()
        );

        sendPacket(viewer, create);
        sendPacket(viewer, new WrapperPlayServerDisplayScoreboard(BELOW_NAME_SLOT, objectiveName));
    }

    public void removeObjective(Player viewer, String objectiveName) {
        sendPacket(viewer, new WrapperPlayServerScoreboardObjective(
                objectiveName,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                Component.empty(),
                null
        ));
    }

    /**
     * @param entry       the target's exact username, matching the entry shown under their nametag
     * @param coloredText already color-coded (legacy '&' or '§') text to render below the target's name
     */
    public void updateEntry(Player viewer, String objectiveName, String entry, String coloredText) {
        PacketWrapper<?> packet = new WrapperPlayServerUpdateScore(
                entry,
                WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                objectiveName,
                PLACEHOLDER_SCORE,
                null,
                ScoreFormat.fixedScore(toComponent(coloredText))
        );

        sendPacket(viewer, packet);
    }

    public void removeEntry(Player viewer, String objectiveName, String entry) {
        sendPacket(viewer, new WrapperPlayServerResetScore(entry, objectiveName));
    }

    private Component toComponent(String text) {
        String coloredText = ChatColor.translateAlternateColorCodes('&', text);
        return LegacyComponentSerializer.legacySection().deserialize(coloredText);
    }

    private void sendPacket(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }
}