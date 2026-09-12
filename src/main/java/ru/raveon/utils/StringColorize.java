package ru.raveon.utils;

import lombok.experimental.UtilityClass;
import net.md_5.bungee.api.ChatColor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@UtilityClass
public class StringColorize {

    private static final char COLOR_CHAR = '&';

    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("&#([a-fA-F0-9]{6})");
    private static final Pattern MC_HEX_PATTERN = Pattern.compile("§x(§[a-fA-F0-9]){6}");
    private static final Pattern CLEAN_HEX_PATTERN = Pattern.compile("^[a-fA-F0-9]{6}$");

    public String parse(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String withHex = replaceAmpHex(message);
        return ChatColor.translateAlternateColorCodes(COLOR_CHAR, withHex);
    }

    public List<String> parse(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        return messages.stream()
                .map(StringColorize::parse)
                .toList();
    }

    public String convertMinecraftColorCodes(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String converted = replaceMinecraftHexToAmpHex(input);
        return converted.replace('§', '&');
    }

    public String convertHexToMinecraftColor(String hex) {
        String clean = normalizeHex(hex);

        StringBuilder result = new StringBuilder(14);
        result.append("&x");
        for (int i = 0; i < clean.length(); i++) {
            result.append(COLOR_CHAR).append(clean.charAt(i));
        }
        return result.toString();
    }

    public String generateGradientString(String text, String startColorHex, String endColorHex, String addPart) {
        if (text == null) {
            throw new IllegalArgumentException("text не может быть null");
        }
        if (text.isEmpty()) {
            return text;
        }

        String start = normalizeHex(startColorHex);
        String end = normalizeHex(endColorHex);
        String extra = addPart == null ? "" : addPart;

        int len = text.length();
        int steps = Math.max(1, len - 1);

        StringBuilder out = new StringBuilder(len * (14 + extra.length() + 1));

        IntStream.range(0, len).forEach(i -> {
            double ratio = (double) i / steps;
            String color = interpolateColor(start, end, ratio);
            out.append(convertHexToMinecraftColor(color)).append(extra).append(text.charAt(i));
        });

        return out.toString();
    }

    public String generateGradientString(String text, String startColorHex, String endColorHex) {
        return generateGradientString(text, startColorHex, endColorHex, null);
    }

    private String replaceAmpHex(String message) {
        Matcher matcher = AMP_HEX_PATTERN.matcher(message);
        if (!matcher.find()) {
            return message;
        }

        matcher.reset();
        StringBuilder builder = new StringBuilder(message.length());
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(ChatColor.of("#" + hex).toString()));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String replaceMinecraftHexToAmpHex(String input) {
        Matcher matcher = MC_HEX_PATTERN.matcher(input);
        if (!matcher.find()) {
            return input;
        }

        matcher.reset();
        StringBuilder builder = new StringBuilder(input.length());
        while (matcher.find()) {
            String match = matcher.group();
            String hex = match.substring(2).replace("§", "");
            matcher.appendReplacement(builder, Matcher.quoteReplacement("&#" + hex));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String normalizeHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex не может быть null");
        }

        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!CLEAN_HEX_PATTERN.matcher(clean).matches()) {
            throw new IllegalArgumentException("Неверный hex-код: " + hex);
        }

        return clean;
    }

    private String interpolateColor(String startHex, String endHex, double ratio) {
        int start = Integer.parseInt(startHex, 16);
        int end = Integer.parseInt(endHex, 16);

        int r = interpolateComponent(start, end, 16, ratio);
        int g = interpolateComponent(start, end, 8, ratio);
        int b = interpolateComponent(start, end, 0, ratio);

        return String.format("%02x%02x%02x", r, g, b);
    }

    private int interpolateComponent(int start, int end, int shift, double ratio) {
        int a = (start >> shift) & 0xFF;
        int b = (end >> shift) & 0xFF;
        return (int) Math.round(a + ratio * (b - a));
    }
}