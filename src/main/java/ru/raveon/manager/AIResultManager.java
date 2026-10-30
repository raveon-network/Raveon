package ru.raveon.manager;

import lombok.RequiredArgsConstructor;

import ru.raveon.checks.impl.ai.AimAI;
import ru.raveon.model.ModelId;
import ru.raveon.player.RaveonPlayer;

@RequiredArgsConstructor
public class AIResultManager {

    public void handleAnalyzeResult(RaveonPlayer raveonPlayer, double chance, ModelId model) {
        if (raveonPlayer == null) {
            return;
        }

        AimAI check = raveonPlayer.getCheckManager().getAimAI(model);
        if (check == null) {
            return;
        }

        check.handleAnalyzeResult(chance);
    }

}
