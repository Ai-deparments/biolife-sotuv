package uz.biolife.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uz.biolife.reporting.application.LogReportSender;
import uz.biolife.reporting.application.PublicUrlNotifier;
import uz.biolife.reporting.application.ReportService;
import uz.biolife.reporting.application.TelegramReportSender;
import uz.biolife.support.TestDatabase;

/**
 * The production wiring end to end: a fake tunnel metrics endpoint and a fake Telegram Bot API on one local server.
 * With a bot token and the metrics URL set the application starts, the new public address is announced to every chat
 * once, and reports go to Telegram as HTML plus the Excel document.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@Import(TestDatabase.class)
class TelegramWiringTests {
    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    static final HttpServer FAKE = start();

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                CALLS.add(path + " " + body);
                byte[] out = (path.endsWith("/quicktunnel") ? "{\"hostname\":\"new-name.trycloudflare.com\"}" : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
                // like cloudflared: the metrics endpoint labels its JSON as text/plain
                ex.getResponseHeaders().add("Content-Type", path.endsWith("/quicktunnel") ? "text/plain; charset=utf-8" : "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + FAKE.getAddress().getPort();
        r.add("biolife.reports.telegram.bot-token", () -> "123:TEST");
        r.add("biolife.reports.telegram.chat-id", () -> "11,-22");
        r.add("biolife.reports.telegram.api-url", () -> base);
        r.add("biolife.public-url.metrics-url", () -> base + "/quicktunnel");
    }

    @AfterAll
    static void stop() { FAKE.stop(0); }

    @Autowired ApplicationContext ctx;
    @Autowired ReportService reports;

    @Test
    void telegramSenderAnnouncesTheNewAddressOnceAndDeliversReports() {
        assertThat(ctx.getBeansOfType(TelegramReportSender.class)).hasSize(1);
        assertThat(ctx.getBeansOfType(LogReportSender.class)).isEmpty();
        assertThat(reports.channel()).isEqualTo("telegram");

        PublicUrlNotifier notifier = ctx.getBean(PublicUrlNotifier.class);
        notifier.check();
        notifier.check();   // same address: no second announcement
        List<String> announced = CALLS.stream().filter(c -> c.startsWith("/bot123:TEST/sendMessage")).toList();
        assertThat(announced).hasSize(2);   // one per chat
        assertThat(announced.get(0)).contains("https://new-name.trycloudflare.com").contains("\"chat_id\":\"11\"");
        assertThat(announced.get(1)).contains("\"chat_id\":\"-22\"");

        CALLS.clear();
        assertThat(reports.sendDaily(java.time.LocalDate.of(2026, 9, 1)).getStatus()).isEqualTo("SENT");
        assertThat(CALLS.stream().filter(c -> c.startsWith("/bot123:TEST/sendMessage")).count()).isEqualTo(2);
        assertThat(CALLS.stream().filter(c -> c.startsWith("/bot123:TEST/sendDocument")).toList())
            .hasSize(2).allMatch(c -> c.contains("BioLife-kunlik-2026-09-01.xlsx"));
    }
}
