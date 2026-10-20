package uz.biolife.reporting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Fallback sender while no Telegram bot is configured: writes the report to the log. */
@Component
@ConditionalOnExpression("'${biolife.reports.telegram.bot-token:}'.isBlank()")
public class LogReportSender implements ReportSender {
    private static final Logger log = LoggerFactory.getLogger(LogReportSender.class);
    @Override public String channel() { return "log"; }
    @Override public String recipient() { return "jurnal"; }
    @Override public void send(Report report) {
        log.info("REPORT {}\n{}{}", report.title(), report.text(), report.file() == null ? "" : "\n[Excel: " + report.fileName() + ", " + report.file().length + " bayt]");
    }
}
