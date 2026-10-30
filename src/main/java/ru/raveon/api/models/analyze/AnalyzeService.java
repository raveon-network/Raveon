package ru.raveon.api.models.analyze;

import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;

public interface AnalyzeService {
    default void analyzePlayerFrames(RaveonPlayer player) {
        analyzePlayerFrames(player, ModelId.PRO);
    }

    void analyzePlayerFrames(RaveonPlayer player, ModelId model);
}
