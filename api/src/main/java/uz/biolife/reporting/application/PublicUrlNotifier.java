package uz.biolife.reporting.application;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import uz.biolife.reporting.domain.ReportKv;
import uz.biolife.reporting.infra.ReportKvRepository;

/**
 * The quick Cloudflare tunnel gets a new random address after a restart. This reads the current address from the
 * tunnel's local metrics endpoint and, when it changes (or the report channel changes), announces it in the report
 * chat, so nobody loses the panel after a server reboot.
 */
@Component
@ConditionalOnExpression("!'${biolife.public-url.metrics-url:}'.isBlank()")
public class PublicUrlNotifier {
    private static final Logger log = LoggerFactory.getLogger(PublicUrlNotifier.class);
    private static final java.util.regex.Pattern HOSTNAME = java.util.regex.Pattern.compile("\"hostname\"\\s*:\\s*\"([A-Za-z0-9.-]+)\"");
    private final RestClient client;
    private final java.net.URI metrics;
    private final ReportSender sender;
    private final ReportKvRepository kv;
    private final Clock clock;

    public PublicUrlNotifier(@Value("${biolife.public-url.metrics-url}") String metricsUrl,
                             org.springframework.beans.factory.ObjectProvider<RestClient.Builder> builders,
                             ReportSender sender, ReportKvRepository kv, Clock clock) {
        this.client = builders.getIfAvailable(RestClient::builder).build();
        this.metrics = java.net.URI.create(metricsUrl.trim());
        this.sender = sender; this.kv = kv; this.clock = clock;
    }

    @Scheduled(initialDelay = 45_000, fixedDelay = 120_000)
    @Transactional
    public void check() {
        String host;
        try {
            // cloudflared answers {"hostname":"…"} with Content-Type text/plain, so read text and pick the field
            String body = client.get().uri(metrics).retrieve().body(String.class);
            java.util.regex.Matcher m = HOSTNAME.matcher(body == null ? "" : body);
            host = m.find() ? m.group(1) : null;
        } catch (RuntimeException e) {
            log.debug("tunnel metrics unavailable: {}", e.getMessage());
            return;
        }
        if (host == null || host.isBlank()) return;
        String url = "https://" + host;
        String key = "public_url:" + sender.channel();
        ReportKv last = kv.findById(key).orElseGet(() -> new ReportKv(key));
        if (url.equals(last.getValue())) return;
        try {
            sender.send(Report.message("Yangi manzil", "🔗 <b>BioLife Agent AI</b> rahbar paneli manzili:\n" + url
                + "\n<i>Server qayta ishga tushgani uchun manzil yangilandi. Eski havola endi ochilmaydi.</i>"));
        } catch (RuntimeException e) {
            log.warn("public url announce failed: {}", e.getMessage());
            return;
        }
        last.set(url, clock.instant());
        kv.save(last);
        log.info("public url announced via {}: {}", sender.channel(), url);
    }
}
