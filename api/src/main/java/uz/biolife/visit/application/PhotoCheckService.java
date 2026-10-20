package uz.biolife.visit.application;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.biolife.shared.events.DomainEvents.PhotoChecked;
import uz.biolife.shared.persistence.AggregateLock;
import uz.biolife.shared.web.NotFoundException;

/**
 * AI check of shelf photos: the worker claims a batch of visits with photos (today and yesterday, an equal share per agent,
 * newest first), looks at the photos and reports what it saw; this service turns that into a verdict against the standard
 * and publishes {@code PhotoChecked} once (reporting counts it as the day's shelf-photo standard).
 * About 5 000 visits a day come with photos and the Claude subscription is shared, so not every visit gets checked:
 * the fair order makes the checked ones a sample of every agent.
 */
@Service
public class PhotoCheckService {
    private static final Logger log = LoggerFactory.getLogger(PhotoCheckService.class);
    static final Duration LEASE = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 4;
    static final int LOOKBACK_DAYS = 1;
    static final int MAX_BATCH = 20;
    public static final Set<String> KINDS = Set.of("shelf", "outside", "other");
    public static final List<String> BRANDS = List.of("BIOLIFE", "FRUCTIS", "Moxito", "Time Tea", "Sitro");

    public record Item(UUID visitId, UUID agentId, String agentName, LocalDate date, String outletName, List<String> photoUrls, int attempt) {}
    /** What the worker needs for one batch: the standard and the visits. */
    public record Batch(int expected, String standardText, List<Item> items) {}
    /** What the worker saw on the photos of one visit. The verdict is computed here, not by the model. */
    public record Result(String kind, Boolean clear, Integer facings, Map<String, Integer> brands, List<String> competitors,
                         String note, Integer photosSeen, String model) {}
    public record Settings(int expectedFacings, String standardText, Instant updatedAt) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final AggregateLock lock;
    private final Clock clock;

    public PhotoCheckService(NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events, AggregateLock lock, Clock clock) {
        this.jdbc = jdbc; this.events = events; this.lock = lock; this.clock = clock;
    }

    /** ok — standard met; low — BioLife on the shelf but fewer facings; none — no BioLife; unclear — cannot count; not_shelf — not a shelf photo. */
    public static String verdict(String kind, boolean clear, int facings, int expected) {
        if (!"shelf".equals(kind)) return "not_shelf";
        if (!clear) return "unclear";
        if (facings <= 0) return "none";
        return facings >= Math.max(1, expected) ? "ok" : "low";
    }

    @Transactional
    public Batch claim(int limit) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        MapSqlParameterSource p = new MapSqlParameterSource("from", today.minusDays(LOOKBACK_DAYS)).addValue("today", today)
            .addValue("now", ts(now)).addValue("max", MAX_ATTEMPTS).addValue("limit", Math.max(1, Math.min(MAX_BATCH, limit)));
        List<Item> picked = jdbc.query("""
            with done as (
                select agent_id, date, count(*) as n from visit_photo_check
                where date >= :from and status = 'done' group by agent_id, date),
            cand as (
                select v.visit_id, v.agent_id, v.date, v.started_at, v.outlet_name, v.photo_urls, coalesce(c.attempts, 0) as attempts,
                       row_number() over (partition by v.agent_id, v.date order by v.started_at desc, v.visit_id) as rn
                from visit_record v left join visit_photo_check c on c.visit_id = v.visit_id
                where v.date between :from and :today and v.photos > 0 and v.photo_urls is not null
                  and (c.visit_id is null or (c.status = 'leased' and c.lease_until < :now and c.attempts < :max)))
            select cand.*, a.name as agent_name from cand
            left join done d on d.agent_id = cand.agent_id and d.date = cand.date
            left join visit_agent a on a.id = cand.agent_id
            order by cand.date desc, coalesce(d.n, 0) + cand.rn, cand.started_at desc, cand.visit_id
            limit :limit""", p, (rs, n) -> new Item(rs.getObject("visit_id", UUID.class), rs.getObject("agent_id", UUID.class),
                rs.getString("agent_name"), rs.getObject("date", LocalDate.class), rs.getString("outlet_name"),
                List.of(rs.getString("photo_urls").split("\n")), rs.getInt("attempts") + 1));
        List<Item> items = new ArrayList<>();
        for (Item it : picked) {
            int leased = jdbc.update("""
                insert into visit_photo_check (visit_id, agent_id, date, status, attempts, lease_until, updated_at)
                values (:v, :a, :d, 'leased', 1, :lease, :now)
                on conflict (visit_id) do update set status = 'leased', attempts = visit_photo_check.attempts + 1,
                    lease_until = :lease, updated_at = :now
                where visit_photo_check.status = 'leased' and visit_photo_check.lease_until < :now""",
                new MapSqlParameterSource("v", it.visitId()).addValue("a", it.agentId()).addValue("d", it.date())
                    .addValue("lease", ts(now.plus(LEASE))).addValue("now", ts(now)));
            if (leased == 1) items.add(it);
        }
        Settings s = settings();
        return new Batch(s.expectedFacings(), s.standardText(), items);
    }

    @Transactional
    public void result(UUID visitId, Result b) {
        lock.acquire("visit:photo:" + visitId);
        Map<String, Object> row = check(visitId);
        if ("done".equals(row.get("status"))) return;
        String kind = b.kind() != null && KINDS.contains(b.kind()) ? b.kind() : "other";
        boolean clear = !Boolean.FALSE.equals(b.clear());
        int facings = Math.max(0, Math.min(500, b.facings() == null ? 0 : b.facings()));
        int expected = settings().expectedFacings();
        String verdict = verdict(kind, clear, facings, expected);
        Instant now = clock.instant();
        jdbc.update("""
            update visit_photo_check set status = 'done', lease_until = null, error = null, kind = :kind, clear = :clear,
                facings = :facings, expected = :expected, verdict = :verdict, brands = :brands, competitors = :competitors,
                note = :note, photos_seen = :seen, model = :model, checked_at = :now, updated_at = :now
            where visit_id = :v""", new MapSqlParameterSource("v", visitId).addValue("kind", kind).addValue("clear", clear)
                .addValue("facings", facings).addValue("expected", expected).addValue("verdict", verdict)
                .addValue("brands", cut(brands(b.brands()), 400), Types.VARCHAR).addValue("competitors", cut(competitors(b.competitors()), 200), Types.VARCHAR)
                .addValue("note", cut(b.note(), 400), Types.VARCHAR).addValue("seen", b.photosSeen(), Types.INTEGER)
                .addValue("model", cut(b.model(), 80), Types.VARCHAR).addValue("now", ts(now)));
        events.publishEvent(new PhotoChecked(visitId, (UUID) row.get("agent_id"), ((java.sql.Date) row.get("date")).toLocalDate(), verdict, facings, expected));
        log.info("photo of visit {} checked: {} ({} facings, standard {})", visitId, verdict, facings, expected);
    }

    /** Worker could not finish. Limit or login waits do not count as an attempt. */
    @Transactional
    public void fail(UUID visitId, String error, boolean retryable, int delaySec, boolean countAttempt) {
        lock.acquire("visit:photo:" + visitId);
        Map<String, Object> row = check(visitId);
        if ("done".equals(row.get("status"))) return;
        Instant now = clock.instant();
        int attempts = ((Number) row.get("attempts")).intValue() - (countAttempt ? 0 : 1);
        boolean giveUp = !retryable || attempts >= MAX_ATTEMPTS;
        long backoff = delaySec > 0 ? delaySec : new long[] {300, 1800, 3600}[Math.min(2, Math.max(0, attempts - 1))];
        jdbc.update("update visit_photo_check set status = :status, attempts = :attempts, lease_until = :until, error = :error, updated_at = :now where visit_id = :v",
            new MapSqlParameterSource("v", visitId).addValue("status", giveUp ? "failed" : "leased").addValue("attempts", Math.max(0, attempts))
                .addValue("until", giveUp ? null : ts(now.plusSeconds(Math.min(6 * 3600, backoff))), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("error", cut(error, 1000), Types.VARCHAR).addValue("now", ts(now)));
    }

    @Transactional(readOnly = true)
    public Settings settings() {
        List<Settings> s = jdbc.query("select expected_facings, standard_text, updated_at from visit_photo_settings where id = 1", new MapSqlParameterSource(),
            (rs, n) -> {
                OffsetDateTime at = rs.getObject("updated_at", OffsetDateTime.class);
                return new Settings(rs.getInt("expected_facings"), rs.getString("standard_text"), at == null ? null : at.toInstant());
            });
        return s.isEmpty() ? new Settings(1, null, null) : s.get(0);
    }

    @Transactional
    public Settings updateSettings(Integer expectedFacings, String standardText) {
        int expected = expectedFacings == null ? 1 : expectedFacings;
        if (expected < 1 || expected > 200) throw new IllegalArgumentException("Kutilgan yuzlar soni 1 dan 200 gacha bo'lsin");
        String text = standardText == null || standardText.isBlank() ? null : cut(standardText.strip(), 2000);
        jdbc.update("""
            insert into visit_photo_settings (id, expected_facings, standard_text, updated_at) values (1, :e, :t, :now)
            on conflict (id) do update set expected_facings = :e, standard_text = :t, updated_at = :now""",
            new MapSqlParameterSource("e", expected).addValue("t", text, Types.VARCHAR).addValue("now", ts(clock.instant())));
        return settings();
    }

    private Map<String, Object> check(UUID visitId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select status, attempts, agent_id, date from visit_photo_check where visit_id = :v",
            new MapSqlParameterSource("v", visitId));
        if (rows.isEmpty()) throw new NotFoundException("Bu vizit rasmi tekshiruvga olinmagan");
        return rows.get(0);
    }

    /** "FRUCTIS:4,Time Tea:3" — only the BioLife brands, positive counts. */
    static String brands(Map<String, Integer> in) {
        if (in == null) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String brand : BRANDS) {
            Integer n = in.get(brand);
            if (n != null && n > 0) out.put(brand, Math.min(500, n));
        }
        if (out.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        out.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ",").append(k).append(':').append(v));
        return sb.toString();
    }

    static String competitors(List<String> in) {
        if (in == null) return null;
        List<String> out = in.stream().filter(c -> c != null && !c.isBlank()).map(c -> c.strip().replace(",", " ")).map(c -> cut(c, 40)).limit(3).toList();
        return out.isEmpty() ? null : String.join(",", out);
    }

    private static OffsetDateTime ts(Instant i) { return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC); }
    private static String cut(String s, int max) { return s == null ? null : s.length() <= max ? s.strip() : s.substring(0, max).strip(); }
}
