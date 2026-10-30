package ru.raveon.menu.players;

import lombok.Getter;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import ru.raveon.api.menu.MenuItem;
import ru.raveon.api.menu.MenuSettings;
import ru.raveon.service.LanguageService;

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

    public PlayersMenuSettings(@NotNull MenuSettings menuSettings, LanguageService language) {
        super(
                menuSettings.getTitle(),
                menuSettings.getSize(),
                menuSettings.getDecoration(),
                menuSettings.getItems(),
                menuSettings.getSection());

        ConfigurationSection settings =
                menuSettings.getSection().getConfigurationSection("settings");

        if (settings == null) {
            this.playerSlots = new ArrayList<>();
            this.playerForm = null;
            this.checkLines = 2;
            this.probabilitiesPerLine = 5;
            this.probabilityMultiplier = 1.0D;
            this.emptyValue = "&7—";
            this.noChecksMessage = language.text("menus.players.no_checks");
            this.barSymbol = "■";
            this.emptyBarSymbol = "  ";
            return;
        }

        this.playerSlots = MenuSettings.validateSlots(settings.getIntegerList("player_slots"), getSize());

        ConfigurationSection playerFormSection = settings.getConfigurationSection("player_form");
        this.playerForm =
                playerFormSection == null ? null : MenuItem.fromSection(playerFormSection);

        this.checkLines = settings.getInt("check_lines", 2);
        this.probabilitiesPerLine = settings.getInt("probs_per_line", 5);
        if (checkLines < 1 || probabilitiesPerLine < 1
                || (long) checkLines * probabilitiesPerLine > 1000) {
            throw new IllegalArgumentException("Menu checks limit must be between 1 and 1000");
        }
        this.probabilityMultiplier = settings.getDouble("probability_multiplier", 1.0D);
        if (!Double.isFinite(probabilityMultiplier) || probabilityMultiplier < 0) {
            throw new IllegalArgumentException("Probability multiplier must be finite and non-negative");
        }

        this.emptyValue = settings.getString("empty_value", "&7—");
        this.noChecksMessage = settings.getString("no_checks_message", language.text("menus.players.no_checks"));
        this.barSymbol = settings.getString("bar_symbol", "■");
        this.emptyBarSymbol = settings.getString("empty_bar_symbol", "  ");
    }

    public static PlayersMenuSettings fromSection(@NotNull ConfigurationSection section, LanguageService language) {
        return new PlayersMenuSettings(MenuSettings.fromSection(language.localize(section)), language);
    }

    public int getChecksLimit() {
        return checkLines * probabilitiesPerLine;
    }
}
