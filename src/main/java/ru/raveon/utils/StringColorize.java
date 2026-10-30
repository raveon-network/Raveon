package ru.raveon.utils;

import lombok.experimental.UtilityClass;

import net.md_5.bungee.api.ChatColor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class StringColorize {

    private static final char COLOR_CHAR = '&';

    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("&#([a-fA-F0-9]{6})");

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

        return messages.stream().map(StringColorize::parse).toList();
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
            matcher.appendReplacement(
                    builder, Matcher.quoteReplacement(ChatColor.of("#" + hex).toString()));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

}
