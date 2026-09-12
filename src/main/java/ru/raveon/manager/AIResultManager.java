package ru.raveon.manager;

import lombok.RequiredArgsConstructor;
import ru.raveon.Raveon;
import ru.raveon.checks.impl.ai.AimAI;
import ru.raveon.player.RaveonPlayer;

@RequiredArgsConstructor
public class AIResultManager {
    public void handleAnalyzeResult(RaveonPlayer raveonPlayer, double chance) {
        if (raveonPlayer == null) {
            return;
        }

        AimAI check = raveonPlayer.getCheckManager().getAimAI();
        if (check == null) {
            return;
        }

        check.handleAnalyzeResult(chance);
    }

    public void handleAnalyzeResult(String username, double chance) {
        RaveonPlayer raveonPlayer = Raveon.INSTANCE.getPlayerDataManager().getPlayer(username);
        if (raveonPlayer == null){
            return;
        }

        AimAI check = raveonPlayer.getCheckManager().getAimAI();
        if (check == null) {
            return;
        }

        check.handleAnalyzeResult(chance);
    }
}