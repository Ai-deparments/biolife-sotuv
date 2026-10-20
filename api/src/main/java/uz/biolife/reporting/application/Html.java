package uz.biolife.reporting.application;

import java.util.regex.Pattern;

/** Telegram HTML helpers: escape user text, strip markup for the plain-text copy. */
final class Html {
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private Html() {}

    static String esc(String s) {
        if (s == null || s.isBlank()) return "—";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String strip(String html) {
        return TAG.matcher(html).replaceAll("").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
