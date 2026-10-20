package uz.biolife.reporting.application;

/** Delivery channel for reports. Implementations: log (no bot configured), Telegram (bot token + chat ids). */
public interface ReportSender {
    String channel();
    String recipient();
    void send(Report report);
}
