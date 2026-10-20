package uz.biolife.reporting.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Sends reports through a Telegram bot: HTML message (bold numbers) and the Excel file as a document.
 * Several chats: biolife.reports.telegram.chat-id = "id1,id2". Only sendMessage/sendDocument are used,
 * so a bot that another service polls can be shared without conflict.
 */
@Component
@ConditionalOnExpression("!'${biolife.reports.telegram.bot-token:}'.isBlank()")
public class TelegramReportSender implements ReportSender {
    static final int LIMIT = 4000;
    private final RestClient client;
    private final List<String> chatIds;

    /** Spring Boot 4 has no RestClient.Builder bean without the restclient starter: build one when none is offered. */
    @org.springframework.beans.factory.annotation.Autowired
    public TelegramReportSender(@Value("${biolife.reports.telegram.bot-token}") String token,
                                @Value("${biolife.reports.telegram.chat-id:}") String chatIds,
                                @Value("${biolife.reports.telegram.api-url:https://api.telegram.org}") String apiUrl,
                                org.springframework.beans.factory.ObjectProvider<RestClient.Builder> builders) {
        this(token, chatIds, apiUrl, builders.getIfAvailable(RestClient::builder));
    }

    public TelegramReportSender(String token, String chatIds, String apiUrl, RestClient.Builder builder) {
        this.client = builder.baseUrl(apiUrl.replaceAll("/+$", "") + "/bot" + token.trim()).build();
        this.chatIds = Arrays.stream(chatIds.split("[,;\\s]+")).filter(s -> !s.isBlank()).toList();
        if (this.chatIds.isEmpty()) throw new IllegalStateException("Telegram hisobot uchun BIOLIFE_REPORT_CHAT_ID kiritilmagan");
    }

    @Override public String channel() { return "telegram"; }
    @Override public String recipient() { return String.join(",", chatIds); }

    @Override
    public void send(Report report) {
        for (String chat : chatIds) {
            for (String part : chunks(report.html())) {
                client.post().uri("/sendMessage").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chat, "text", part, "parse_mode", "HTML", "disable_web_page_preview", true))
                    .retrieve().toBodilessEntity();
            }
            if (report.file() != null) {
                MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
                form.add("chat_id", chat);
                if (report.fileCaption() != null) form.add("caption", report.fileCaption());
                form.add("document", new ByteArrayResource(report.file()) {
                    @Override public String getFilename() { return report.fileName(); }
                });
                client.post().uri("/sendDocument").contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toBodilessEntity();
            }
        }
    }

    /** Splits on line boundaries so no HTML tag is cut (every tag in our reports opens and closes on one line). */
    static List<String> chunks(String html) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : html.split("\n", -1)) {
            if (cur.length() + line.length() + 1 > LIMIT && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line.length() > LIMIT ? line.substring(0, LIMIT) : line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
