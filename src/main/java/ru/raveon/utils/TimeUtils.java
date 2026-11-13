package ru.raveon.utils;

public final class TimeUtils {
    private TimeUtils() {}

    public static String formatSeconds(long totalSeconds) {
        long remaining = Math.max(0, totalSeconds);
        long[] sizes = {31_536_000, 2_592_000, 86_400, 3_600, 60, 1};
        String[] units = {"yr", "mo", "d", "h", "min", "sec"};
        var parts = new java.util.ArrayList<String>();
        for (int i = 0; i < sizes.length; i++) {
            long count = remaining / sizes[i];
            remaining %= sizes[i];
            if (count > 0 || i == sizes.length - 1 && parts.isEmpty()) parts.add(count + " " + units[i]);
        }
        return String.join(" ", parts);
    }
}
