package ru.raveon.service.train;

import lombok.Getter;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.models.RotationFrame;
import ru.raveon.api.models.UltraFrame;
import ru.raveon.model.ModelId;
import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Getter
public final class UploadService {
    private static volatile DatasetWriter writer;
    private final Plugin plugin;
    private final File datasetFile;
    private final ModelId model;

    public static synchronized void start() {
        if (writer == null) writer = new DatasetWriter(64);
    }

    public static synchronized void shutdown() {
        DatasetWriter current = writer;
        writer = null;
        if (current != null) current.close();
    }

    public UploadService(Plugin plugin, String fileName, String folderName) {
        this(plugin, fileName, folderName, ModelId.PRO);
    }

    public UploadService(Plugin plugin, String fileName, String folderName, ModelId model) {
        this.model = model;
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        File folder = new File(plugin.getDataFolder(), folderName);
        String safeName = fileName == null || fileName.isBlank() ? "dataset"
                : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        this.datasetFile = new File(folder, safeName + ".csv");
        if (!datasetFile.toPath().toAbsolutePath().normalize()
                .startsWith(plugin.getDataFolder().toPath().toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Dataset path is outside the plugin directory");
    }

    public CompletableFuture<Boolean> uploadPlayerData(List<RotationFrame> frames, boolean cheater) {
        DatasetWriter current = writer;
        return current == null ? CompletableFuture.failedFuture(new IllegalStateException("Dataset writer is closed"))
                : current.append(datasetFile.toPath(), RotationFrame.csvHeader(), frames, frame -> frame.toCsvRow(cheater));
    }

    public CompletableFuture<Boolean> uploadUltraPlayerData(List<UltraFrame> frames, boolean cheater) {
        DatasetWriter current = writer;
        return current == null ? CompletableFuture.failedFuture(new IllegalStateException("Dataset writer is closed"))
                : current.append(datasetFile.toPath(), UltraFrame.csvHeader(), frames, frame -> frame.toCsvRow(cheater));
    }
}
