    package ru.raveon.config.anticheat;

    import lombok.Getter;
    import org.bukkit.entity.Player;
    import org.bukkit.plugin.Plugin;
    import ru.raveon.api.configuration.ConfigManager;
    import ru.raveon.api.configuration.CustomConfig;
    import ru.raveon.integration.worldguard.WorldGuardRegionBypassConfig;
    import ru.raveon.integration.worldguard.WorldGuardRegionBypassService;

    @Getter
    public class ChecksConfigManager extends ConfigManager {
        private int analysisSequence;
        private int analysisStep;

        private long combatTimer;

        private String analyzeServer;
        private WorldGuardRegionBypassService worldGuardRegionBypassService;

        // AimAI
        private double aimAiCheatProbability;
        private double aimAiBufferFlagThreshold;
        private double aimAiBufferResetOnFlag;
        private double aimAiBufferMultiplier;
        private double aimAiBufferDecrease;

        public ChecksConfigManager(Plugin plugin) {
            super(plugin);
        }

        @Override
        public void loadConfigs() {
            addCustomConfig(new CustomConfig("checks.yml", plugin));
        }

        @Override
        public void loadValues() {
            CustomConfig checksConfig = getCustomConfig("checks.yml");

            analysisSequence = checksConfig.getInt("analyze.sequence", 50);
            analysisStep = checksConfig.getInt("analyze.step", 10);
            combatTimer = checksConfig.getInt("analyze.combat", 5) * 50L;

            analyzeServer = checksConfig.getString("analyze.analyze_server", "https://api.raveonai.wtf/v1/inference");
            worldGuardRegionBypassService = new WorldGuardRegionBypassService(
                    plugin,
                    WorldGuardRegionBypassConfig.fromConfig(checksConfig, "analyze.worldguard")
            );

            aimAiCheatProbability = clamp01(checksConfig.getDouble("analyze.probability", 0.85D));
            aimAiBufferFlagThreshold = Math.max(0.0D, checksConfig.getDouble("analyze.buffer.flag", 50.0D));
            aimAiBufferResetOnFlag = Math.max(
                    0.0D,
                    Math.min(aimAiBufferFlagThreshold, checksConfig.getDouble("analyze.buffer.reset_on_flag", 25.0D))
            );
            aimAiBufferMultiplier = Math.max(0.0D, checksConfig.getDouble("analyze.buffer.multiplier", 100.0D));
            aimAiBufferDecrease = Math.max(0.0D, checksConfig.getDouble("analyze.buffer.decrease", 0.25D));
        }

        private double clamp01(double value) {
            return Math.max(0.0D, Math.min(1.0D, value));
        }

        public CustomConfig getChecksConfig() {
            return super.getCustomConfig("checks.yml");
        }

        public boolean isAimAiBypassedInRegion(Player player) {
            return worldGuardRegionBypassService != null && worldGuardRegionBypassService.isBypassed(player);
        }
    }