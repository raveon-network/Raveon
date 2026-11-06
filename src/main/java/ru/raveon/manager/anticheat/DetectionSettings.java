package ru.raveon.manager.anticheat;

import ru.raveon.model.ModelId;
import ru.raveon.config.anticheat.ChecksConfigManager.*;
import java.util.Map;

/** Immutable runtime policy view, independent of a player's connection. */
public interface DetectionSettings {
    boolean isAnalysisEnabled();
    boolean isModelEnabled(ModelId model);
    Map<ModelId, AnalysisSettings> getModelSettings();
    BufferSettings getBufferSettings();
    ModelId getPrimaryModel();
    MitigationSettings getMitigationSettings();
    int getAnalysisStep(ModelId model);
    int getAnalysisSequence(ModelId model);
}
