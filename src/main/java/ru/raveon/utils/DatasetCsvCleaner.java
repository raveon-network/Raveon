package ru.raveon.utils;

import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public final class DatasetCsvCleaner {
    private static final List<String> FIELDS_TO_REMOVE = List.of(
            "angle_to_target",
            "delta_angle_to_target",
            "yaw_to_target_diff",
            "pitch_to_target_diff"
    );

    private final Plugin plugin;

    public void clean() throws IOException {
        Path datasetsPath = plugin.getDataFolder().toPath().resolve("datasets");
        if (!Files.exists(datasetsPath) || !Files.isDirectory(datasetsPath)) {
            return;
        }

        try (var paths = Files.walk(datasetsPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isCsvFile)
                    .forEach(this::cleanCsvSilently);
        }
    }

    private boolean isCsvFile(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".csv");
    }

    private void cleanCsvSilently(Path file) {
        try {
            cleanCsv(file);
        } catch (IOException ignored) {
        }
    }

    private void cleanCsv(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return;
        }

        String[] headers = lines.get(0).split(",", -1);
        List<Integer> keepIndexes = new ArrayList<>();
        List<String> filteredHeaders = new ArrayList<>();

        for (int i = 0; i < headers.length; i++) {
            String header = headers[i].trim();
            if (!FIELDS_TO_REMOVE.contains(header)) {
                keepIndexes.add(i);
                filteredHeaders.add(headers[i]);
            }
        }

        if (keepIndexes.size() == headers.length) {
            return;
        }

        List<String> result = new ArrayList<>(lines.size());
        result.add(String.join(",", filteredHeaders));

        for (int i = 1; i < lines.size(); i++) {
            String[] values = lines.get(i).split(",", -1);
            List<String> filteredRow = new ArrayList<>(keepIndexes.size());

            for (int index : keepIndexes) {
                filteredRow.add(index < values.length ? values[index] : "");
            }

            result.add(String.join(",", filteredRow));
        }

        Files.write(file, result, StandardCharsets.UTF_8);
    }
}