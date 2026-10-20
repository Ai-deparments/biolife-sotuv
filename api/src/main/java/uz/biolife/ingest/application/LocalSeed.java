package uz.biolife.ingest.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import uz.biolife.identity.application.AuthService;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.security.CurrentUser.Role;
import org.springframework.beans.factory.annotation.Value;
import uz.biolife.shared.events.DomainEvents.AnalysisCompleted;
import uz.biolife.shared.events.DomainEvents.DayPlanned;
import uz.biolife.shared.events.DomainEvents.OrderConfirmed;
import uz.biolife.shared.events.DomainEvents.PhotoChecked;
import uz.biolife.shared.events.DomainEvents.VisitClosed;
import uz.biolife.shared.events.DomainEvents.VisitStarted;
import uz.biolife.shared.time.WorkHours;

/**
 * Local-profile demo data: 6 agents, last 14 working days, deterministic (seeded Random).
 * Goes through the same event path as production, so listeners are exercised exactly as in prod.
 */
@Component
@ConditionalOnProperty(prefix = "biolife.seed", name = "enabled", havingValue = "true")
public class LocalSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalSeed.class);
    private static final String[] CRITERIA = {"salom", "ehtiyoj", "taklif", "aksiya", "yangi", "etiroz", "yakun"};
    private static final int[] WEIGHTS = {10, 20, 20, 15, 10, 15, 10};

    record Agent(UUID id, String name, String region, String district, double skill) {}

    private final FactPublisher publisher;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuthService auth;
    private final String managerPassword;
    private final String agentPassword;

    public LocalSeed(FactPublisher publisher, JdbcTemplate jdbc, Clock clock, AuthService auth,
                     @Value("${biolife.seed.manager-password}") String managerPassword, @Value("${biolife.seed.agent-password}") String agentPassword) {
        this.publisher = publisher; this.jdbc = jdbc; this.clock = clock; this.auth = auth; this.managerPassword = managerPassword; this.agentPassword = agentPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        Random rnd = new Random(42);
        List<Agent> agents = List.of(
            new Agent(UUID.nameUUIDFromBytes("a1".getBytes()), "Dilnoza R.", "Andijon", "Andijon sh.", 0.92),
            new Agent(UUID.nameUUIDFromBytes("a2".getBytes()), "Sardor T.", "Toshkent", "Yunusobod", 0.74),
            new Agent(UUID.nameUUIDFromBytes("a3".getBytes()), "Nilufar X.", "Buxoro", "Buxoro sh.", 0.80),
            new Agent(UUID.nameUUIDFromBytes("a4".getBytes()), "Akmal S.", "Samarqand", "Samarqand sh.", 0.66),
            new Agent(UUID.nameUUIDFromBytes("a5".getBytes()), "Jasur K.", "Toshkent", "Chilonzor", 0.52),
            new Agent(UUID.nameUUIDFromBytes("a6".getBytes()), "Olim B.", "Xorazm", "Urganch", 0.41));
        if (!auth.hasUsers()) {
            auth.createUser(UUID.nameUUIDFromBytes("manager".getBytes()), "rahbar", managerPassword, "Abbos (kommersiya direktori)", Role.MANAGER);
            for (Agent a : agents) auth.createUser(a.id(), a.name().split(" ")[0].toLowerCase(), agentPassword, a.name(), Role.AGENT);
            log.info("seed: login accounts created (rahbar + {} agents)", agents.size());
        }
        Integer existing = jdbc.queryForObject("select count(*) from rating_agent", Integer.class);
        if (existing != null && existing > 0) { log.info("seed skipped, {} agents exist", existing); return; }
        for (Agent a : agents) publisher.publish(new AgentProfileChanged(a.id(), a.name(), a.region(), a.district(), true));

        LocalDate today = LocalDate.now(clock);
        for (int back = 13; back >= 0; back--) {
            LocalDate d = today.minusDays(back);
            if (!WorkHours.isWorkDay(d)) continue;
            for (Agent a : agents) seedDay(a, d, rnd, back == 0);
        }
        log.info("seed done: {} agents, 14 days", agents.size());
    }

    private void seedDay(Agent a, LocalDate d, Random rnd, boolean today) {
        int planned = 8;
        publisher.publish(new DayPlanned(a.id(), d, planned));
        int done = today ? 3 : (int) Math.round(planned * (0.5 + a.skill() * 0.5));
        int startMin = (int) (9 * 60 + (a.skill() < 0.6 ? 20 + rnd.nextInt(70) : rnd.nextInt(30)));
        for (int i = 0; i < done; i++) {
            UUID visit = UUID.randomUUID();
            long step = done <= 1 ? 0 : (int) ((17 * 60 + 40 - startMin) / (double) (done - 1));
            var start = ZonedDateTime.of(d.atTime(0, 0), WorkHours.ZONE).plusMinutes(startMin + i * step).toInstant();
            boolean excused = rnd.nextDouble() < 0.08;
            boolean suspect = !excused && rnd.nextDouble() < (1 - a.skill()) * 0.15;
            publisher.publish(new VisitStarted(visit, a.id(), start, !suspect));
            int blocks = excused ? 0 : 8 + rnd.nextInt(30);
            publisher.publish(new VisitClosed(visit, a.id(), d, start.plusSeconds(600), !excused && !suspect, excused, suspect, blocks));
            if (excused) continue;
            Map<String, Integer> crit = new java.util.LinkedHashMap<>();
            int score = 0;
            for (int c = 0; c < CRITERIA.length; c++) {
                double p = Math.min(1, Math.max(0, a.skill() + (rnd.nextDouble() - 0.5) * 0.5));
                int pts = (int) Math.round(WEIGHTS[c] * p);
                crit.put(CRITERIA[c], pts);
                score += pts;
            }
            List<String> flags = suspect ? List.of("single_voice") : List.of();
            publisher.publish(new AnalysisCompleted(UUID.randomUUID(), visit, a.id(), d, suspect ? 0 : score, crit,
                crit.get("aksiya") >= 10, flags, score >= 90 ? "Ehtiyoj savollari va qo'shni do'kon misoli, " + blocks + " blok buyurtma." : "Standart suhbat, " + blocks + " blok."));
            publisher.publish(new PhotoChecked(visit, a.id(), d, rnd.nextDouble() < a.skill() ? "ok" : "low", 6 + rnd.nextInt(16), 12));
            if (!suspect) publisher.publish(new OrderConfirmed(visit, a.id(), d, blocks * 48_000L, blocks));
        }
    }
}
