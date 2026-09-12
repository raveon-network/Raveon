package ru.raveon.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TimeUtils {
    public String formatSeconds(int totalSeconds) {
        int years = totalSeconds / (365 * 24 * 3600);
        totalSeconds %= 365 * 24 * 3600;

        int months = totalSeconds / (30 * 24 * 3600);
        totalSeconds %= 30 * 24 * 3600;

        int days = totalSeconds / (24 * 3600);
        totalSeconds %= 24 * 3600;

        int hours = totalSeconds / 3600;
        totalSeconds %= 3600;

        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;

        StringBuilder builder = new StringBuilder();

        if (years > 0) builder.append(years).append(" г. ");
        if (months > 0) builder.append(months).append(" мес. ");
        if (days > 0) builder.append(days).append(" д. ");
        if (hours > 0) builder.append(hours).append(" ч. ");
        if (minutes > 0) builder.append(minutes).append(" мин. ");
        if (seconds > 0 || builder.isEmpty())
            builder.append(seconds).append(" сек");

        return builder.toString().trim();
    }

}
