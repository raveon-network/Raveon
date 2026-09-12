package ru.raveon.api.models.analyze;

import ru.raveon.api.models.RotationFrame;
import ru.raveon.player.RaveonPlayer;

import java.util.List;

public record AnalyticData(String username, List<RotationFrame> rotationFrames) {

    public static AnalyticData createData(RaveonPlayer raveonPlayer) {
        final List<RotationFrame> rotationFrames = raveonPlayer.getRotationBuffer().getSnapshot();

        raveonPlayer.setLastAnalyzedFrames(rotationFrames);

        return new AnalyticData(raveonPlayer.getName(), rotationFrames);
    }

}