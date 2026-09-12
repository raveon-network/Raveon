package ru.raveon.menu.players;

import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.MenuSettings;

import java.util.ArrayList;
import java.util.List;

@Getter
public class PlayersMenuSettings extends MenuSettings {
    private final List<Integer> playerSlots;
    private final MenuItem playerForm;

    private final int checkLines;
    private final int probabilitiesPerLine;
    private final double probabilityMultiplier;

    private final String emptyValue;
    private final String noChecksMessage;
    private final String barSymbol;
    private final String emptyBarSymbol;

    public PlayersMenuSettings(@NotNull MenuSettings menuSettings) {
        super(
                menuSettings.getTitle(),
                menuSettings.getSize(),
                menuSettings.getDecoration(),
                menuSettings.getItems(),
                menuSettings.getSection()
        );

        ConfigurationSection settings = menuSettings.getSection().getConfigurationSection("settings");

        if (settings == null) {
            this.playerSlots = new ArrayList<>();
            this.playerForm = null;
            this.checkLines = 2;
            this.probabilitiesPerLine = 5;
            this.probabilityMultiplier = 1.0D;
            this.emptyValue = "&7—";
            this.noChecksMessage = "&7Нет данных";
            this.barSymbol = "■";
            this.emptyBarSymbol = "  ";
            return;
        }

        this.playerSlots = new ArrayList<>(settings.getIntegerList("player_slots"));

        ConfigurationSection playerFormSection = settings.getConfigurationSection("player_form");
        this.playerForm = playerFormSection == null ? null : MenuItem.fromSection(playerFormSection);

        this.checkLines = Math.max(1, settings.getInt("check_lines", 2));
        this.probabilitiesPerLine = Math.max(1, settings.getInt("probs_per_line", 5));
        this.probabilityMultiplier = settings.getDouble("probability_multiplier", 1.0D);

        this.emptyValue = settings.getString("empty_value", "&7—");
        this.noChecksMessage = settings.getString("no_checks_message", "&7Нет данных");
        this.barSymbol = settings.getString("bar_symbol", "■");
        this.emptyBarSymbol = settings.getString("empty_bar_symbol", "  ");
    }

    public static PlayersMenuSettings fromSection(@NotNull ConfigurationSection section) {
        return new PlayersMenuSettings(MenuSettings.fromSection(section));
    }

    public int getChecksLimit() {
        return checkLines * probabilitiesPerLine;
    }
}
