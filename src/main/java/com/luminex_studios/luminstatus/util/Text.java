package com.luminex_studios.luminstatus.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small formatting helpers shared by the page renderer and the feed. */
public final class Text {

    private static final DateTimeFormatter RFC_3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private Text() {
    }

    /** Escapes text for safe inclusion in HTML element content and attributes. */
    public static String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static String rfc3339(Instant instant) {
        return RFC_3339.format(instant);
    }

    /** Renders a duration the way an operator reads it: "4m 12s", "3h 05m". */
    public static String humanDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + String.format(Locale.ROOT, "%02ds", seconds % 60);
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h " + String.format(Locale.ROOT, "%02dm", (seconds % 3600) / 60);
        }
        return (seconds / 86400) + "d " + String.format(Locale.ROOT, "%02dh", (seconds % 86400) / 3600);
    }

    /** Lower-cased, hyphenated identifier safe for URLs and file names. */
    public static String slug(String input) {
        String cleaned = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        cleaned = cleaned.replaceAll("(^-|-$)", "");
        return cleaned.isEmpty() ? "component" : cleaned;
    }
}
