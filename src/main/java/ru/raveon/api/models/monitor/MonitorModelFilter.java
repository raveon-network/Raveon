package ru.raveon.api.models.monitor;
import ru.raveon.model.ModelId;
public record MonitorModelFilter(ModelId model) {
    public static final MonitorModelFilter PRO = new MonitorModelFilter(ModelId.PRO);
    public static final MonitorModelFilter ULTRA = new MonitorModelFilter(ModelId.ULTRA);
    public static final MonitorModelFilter ALL = new MonitorModelFilter(null);
    public boolean accepts(ModelId id) { return model == null || model.equals(id); }
    public String displayName() { return title(false); }
    public String fullDisplayName() { return title(true); }
    private String title(boolean full) {
        var checks = ru.raveon.Raveon.INSTANCE.getChecksConfigManager();
        return model == null ? ru.raveon.Raveon.INSTANCE.getLanguageService().text("monitor.models.all")
                : full ? checks.getModelName(model) : checks.getModelShortTitle(model);
    }
    public static MonitorModelFilter parse(String argument) {
        if (argument.equalsIgnoreCase("all")) return ALL;
        ModelId id = ModelId.parse(argument);
        if (!ru.raveon.Raveon.INSTANCE.getChecksConfigManager().activeModels().contains(id))
            throw new IllegalArgumentException("Unknown monitor model: " + argument);
        return new MonitorModelFilter(id);
    }
    public static boolean isArgument(String argument) {
        try { parse(argument); return true; } catch (IllegalArgumentException e) { return false; }
    }
}
