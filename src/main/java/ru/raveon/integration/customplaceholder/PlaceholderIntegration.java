package ru.raveon.integration.customplaceholder;

import org.bukkit.plugin.Plugin;

public interface PlaceholderIntegration {
    void init(Plugin plugin);

    String getPlaceholder(String path);

    String getPlaceholder(String path, String def);
}
