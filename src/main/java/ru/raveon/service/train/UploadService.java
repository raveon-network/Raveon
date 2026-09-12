package ru.raveon.service.train;

import lombok.Getter;
import org.bukkit.plugin.Plugin;
import ru.raveon.api.models.RotationFrame;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Getter
public final class UploadService {
    private final Plugin plugin;
    private final File datasetFile;
    private final Object fileLock = new Object();

    public UploadService(Plugin plugin, String fileName, String folderName) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        File dataFolder = new File(plugin.getDataFolder(), folderName);
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().severe("Не удалось создать директорию: " + dataFolder.getAbsolutePath());
        }
        this.datasetFile = new File(dataFolder, sanitizeFileName(fileName) + ".csv");
        ensureHeader();
    }

    public CompletableFuture<Boolean> uploadPlayerData(List<RotationFrame> frames, boolean isCheater) {
        return CompletableFuture.supplyAsync(() -> {
            if (frames == null || frames.isEmpty()) {
                return false;
            }

            synchronized (fileLock) {
                ensureHeader();
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(datasetFile, true))) {
                    for (RotationFrame frame : frames) {
                        writer.newLine();
                        writer.write(frame.toCsvRow(isCheater));
                    }
                    writer.flush();
                    return true;
                } catch (IOException exception) {
                    plugin.getLogger().severe("Ошибка сохранения датасета: " + exception.getMessage());
                    return false;
                }
            }
        });
    }

    private void ensureHeader() {
        synchronized (fileLock) {
            if (datasetFile.exists() && datasetFile.length() > 0L) {
                return;
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(datasetFile, false))) {
                writer.write(RotationFrame.csvHeader());
                writer.flush();
            } catch (IOException exception) {
                plugin.getLogger().severe("Ошибка записи заголовка CSV: " + exception.getMessage());
            }
        }
    }

    private String sanitizeFileName(String value) {
        return value == null || value.isBlank()
                ? "dataset"
                : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}