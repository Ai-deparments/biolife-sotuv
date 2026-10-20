package uz.biolife.reporting.application;

/**
 * One report ready to deliver: Telegram HTML, the same text without markup (log and history),
 * and an optional Excel attachment.
 */
public record Report(String title, String text, String html, String fileName, byte[] file, String fileCaption) {
    /** A short message without attachment. */
    public static Report message(String title, String html) { return new Report(title, Html.strip(html), html, null, null, null); }
}
