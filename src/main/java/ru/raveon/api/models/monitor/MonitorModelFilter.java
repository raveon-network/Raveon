package ru.raveon.api.models.monitor;

import ru.raveon.model.ModelId;

import java.util.Locale;

public enum MonitorModelFilter {
    PRO,
    ULTRA,
    ALL;

    public boolean accepts(ModelId model) {
        return this == ALL || (this == PRO ? model == ModelId.PRO : model == ModelId.ULTRA);
    }

    public String displayName() {
        return switch (this) {
            case PRO -> "Pro";
            case ULTRA -> "Ultra";
            case ALL -> ru.raveon.Raveon.INSTANCE.getLanguageService().text("monitor.models.all");
        };
    }

    public static MonitorModelFilter parse(String argument) {
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "pro" -> PRO;
            case "ultra" -> ULTRA;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException("Unknown monitor model: " + argument);
        };
    }

    public static boolean isArgument(String argument) {
        return argument.equalsIgnoreCase("pro")
                || argument.equalsIgnoreCase("ultra")
                || argument.equalsIgnoreCase("all");
    }
}
