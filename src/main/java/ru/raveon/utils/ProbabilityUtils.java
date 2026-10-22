package ru.raveon.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ProbabilityUtils {
    public int toPercent(double probability) {
        if (!Double.isFinite(probability)) {
            return 0;
        }

        long rounded = Math.round(probability * 100.0D);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded));
    }

    public String formatPercent(double probability) {
        return String.valueOf(toPercent(probability));
    }
}
