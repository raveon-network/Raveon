package ru.raveon.checks;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import ru.raveon.Raveon;
import ru.raveon.api.configuration.CustomConfig;
import ru.raveon.api.models.AbstractCheck;
import ru.raveon.player.RaveonPlayer;

@Getter
public class Check implements AbstractCheck {
    protected final @NotNull RaveonPlayer player;

    protected double violations;
    private boolean enabled;
    private double decay;

    private String checkName;
    private String configName;
    private String alternativeName;
    private String displayName;
    private String description;

    private boolean experimental;
    protected long lastViolationTime;

    public Check(final @NotNull RaveonPlayer player) {
        this.player = player;

        final CheckData checkData = getClass().getAnnotation(CheckData.class);
        if (checkData != null) {
            this.checkName = checkData.name();
            this.configName = checkData.configName();
            if ("DEFAULT".equals(this.configName)) {
                this.configName = this.checkName;
            }

            this.decay = checkData.decay();
            this.alternativeName = checkData.alternativeName();
            this.experimental = checkData.experimental();
            this.description = checkData.description();
            this.displayName = this.checkName;
        }

        reload();
    }

    @Override
    public String getCheckName() {
        return checkName;
    }

    @Override
    public double getViolations() {
        return violations;
    }

    @Override
    public long getLastViolationTime() {
        return lastViolationTime;
    }

    @Override
    public boolean isExperimental() {
        return experimental;
    }

    public void reload() {
        reload(Raveon.INSTANCE.getChecksConfigManager().getChecksConfig());
    }

    public final boolean flagAndAlert() {
        return flagAndAlert("");
    }

    public final boolean flagAndAlert(String verbose) {
        return flag(verbose);
    }

    protected final boolean flag() {
        return flag("");
    }

    protected boolean flag(String verbose) {
        if (!canFlag()) {
            return false;
        }

        registerViolation();
        handlePunishment(verbose);
        return true;
    }

    protected final void registerViolation() {
        lastViolationTime = System.currentTimeMillis();
        violations++;
    }

    protected void handlePunishment(String verbose) {
        player.getPunishmentManager().handleViolation(this, verbose);
    }

    public boolean alert() {
        return alert("");
    }

    public boolean alert(String verbose) {
        if(!canAlert()){
            return false;
        }

        player.getPunishmentManager().executeAlert(this, verbose);
        return true;
    }

    public final void reward() {
        violations = Math.max(0D, violations - decay);
    }

    protected boolean canFlag() {
        return enabled && !experimental;
    }

    protected boolean canAlert() {
        return enabled;
    }

    public final void reload(CustomConfig configuration) {
        enabled = configuration.getBoolean(configName + ".enable", true);
        displayName = configuration.getConfig().getString(configName + ".display_name", checkName);
        description = configuration.getConfig().getString(configName + ".description", description);

        onReload(configuration);
    }

    public void onReload(CustomConfig config) {
    }
}