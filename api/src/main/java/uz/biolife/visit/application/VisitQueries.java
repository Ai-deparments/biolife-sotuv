package uz.biolife.visit.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.biolife.visit.application.VisitDtos.AgentVisitRow;
import uz.biolife.visit.application.VisitDtos.PhotoAi;
import uz.biolife.visit.application.VisitDtos.RegionRow;
import uz.biolife.visit.application.VisitDtos.VisitPage;
import uz.biolife.visit.application.VisitDtos.VisitRow;

/** Read side of the visit list: filtered pages, branch coverage, per-agent checks. */
@Service
public class VisitQueries {
    /** Filter key → SQL condition on visit_record v. Order is the order of the chips in the panel. */
    static final Map<String, String> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("all", "true");
        KINDS.put("far", "not v.within_radius");
        KINDS.put("outplan", "v.in_plan = false");
        KINDS.put("noorder", "v.orders = 0");
        KINDS.put("nophoto", "v.photos = 0");
        KINDS.put("withphoto", "v.photos > 0");
        KINDS.put("oldphoto", "v.photos_suspect > 0");
        KINDS.put("photook", "pc.verdict = 'ok'");
        KINDS.put("photobad", "pc.verdict in ('low', 'none', 'unclear', 'not_shelf')");
    }
    private static final String FROM = " from visit_record v left join visit_agent a on a.id = v.agent_id"
        + " left join visit_photo_check pc on pc.visit_id = v.visit_id and pc.status = 'done'";
    private static final String PHOTO_COLS = ", pc.verdict as pc_verdict, pc.kind as pc_kind, pc.facings as pc_facings, pc.expected as pc_expected,"
        + " pc.brands as pc_brands, pc.competitors as pc_competitors, pc.note as pc_note, pc.checked_at as pc_checked_at";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public VisitQueries(NamedParameterJdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional(readOnly = true)
    public VisitPage list(LocalDate date, String kind, String region, String q, UUID agentId, int page, int size) {
        LocalDate d = date == null ? LocalDate.now(clock) : date;
        String k = kind != null && KINDS.containsKey(kind) ? kind : "all";
        int sz = Math.max(1, Math.min(200, size)), pg = Math.max(0, page);
        MapSqlParameterSource p = new MapSqlParameterSource("date", d);
        StringBuilder where = new StringBuilder(" where v.date = :date");
        if (region != null && !region.isBlank()) { where.append(" and a.region = :region"); p.addValue("region", region.trim()); }
        if (agentId != null) { where.append(" and v.agent_id = :agentId"); p.addValue("agentId", agentId); }
        if (q != null && !q.isBlank()) {
            where.append(" and (a.name ilike :q or v.outlet_name ilike :q or v.outlet_ref = :qExact)");
            p.addValue("q", "%" + escapeLike(q.trim()) + "%").addValue("qExact", q.trim());
        }
        StringBuilder select = new StringBuilder("select ");
        int i = 0;
        for (Map.Entry<String, String> e : KINDS.entrySet()) {
            if (i++ > 0) select.append(", ");
            select.append("count(*) filter (where ").append(e.getValue()).append(") as c_").append(e.getKey());
        }
        Map<String, Long> counts = jdbc.queryForObject(select + FROM + where, p, (rs, n) -> {
            Map<String, Long> m = new LinkedHashMap<>();
            for (String key : KINDS.keySet()) m.put(key, rs.getLong("c_" + key));
            return m;
        });
        p.addValue("limit", sz).addValue("offset", (long) pg * sz);
        List<VisitRow> items = jdbc.query("select v.*, a.name as agent_name, a.region as region" + PHOTO_COLS + FROM + where
            + " and (" + KINDS.get(k) + ") order by v.started_at desc, v.visit_id limit :limit offset :offset", p, VisitQueries::row);
        List<String> regions = jdbc.queryForList(
            "select distinct region from visit_agent where active and region is not null order by region", new MapSqlParameterSource(), String.class);
        return new VisitPage(d, k, pg, sz, counts == null ? 0 : counts.get(k), counts, regions, items);
    }

    @Transactional(readOnly = true)
    public List<RegionRow> regions(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", from).addValue("to", to);
        Map<String, long[]> m = new HashMap<>();   // 0 agents, 1 active, 2 planned, 3 visits, 4 inPlan, 5 outPlan, 6 far, 7 order, 8 photo, 9 suspect
        jdbc.query("select coalesce(region, 'Filialsiz') as region, count(*) as n from visit_agent where active group by 1", p,
            rs -> { m.computeIfAbsent(rs.getString("region"), x -> new long[10])[0] = rs.getLong("n"); });
        jdbc.query("""
            select coalesce(a.region, 'Filialsiz') as region, sum(pd.planned) as n
            from visit_plan_day pd join visit_agent a on a.id = pd.agent_id
            where pd.date between :from and :to and a.active group by 1""", p,
            rs -> { m.computeIfAbsent(rs.getString("region"), x -> new long[10])[2] = rs.getLong("n"); });
        jdbc.query("""
            select coalesce(a.region, 'Filialsiz') as region, count(distinct v.agent_id) as active, count(*) as visits,
                   count(*) filter (where v.in_plan) as in_plan, count(*) filter (where v.in_plan = false) as out_plan,
                   count(*) filter (where not v.within_radius) as far, count(*) filter (where v.orders > 0) as with_order,
                   count(*) filter (where v.photos > 0) as with_photo, coalesce(sum(v.photos_suspect), 0) as suspect
            from visit_record v join visit_agent a on a.id = v.agent_id
            where v.date between :from and :to group by 1""", p, rs -> {
                long[] r = m.computeIfAbsent(rs.getString("region"), x -> new long[10]);
                r[1] = rs.getLong("active"); r[3] = rs.getLong("visits"); r[4] = rs.getLong("in_plan"); r[5] = rs.getLong("out_plan");
                r[6] = rs.getLong("far"); r[7] = rs.getLong("with_order"); r[8] = rs.getLong("with_photo"); r[9] = rs.getLong("suspect");
            });
        List<RegionRow> out = new ArrayList<>();
        m.forEach((region, r) -> {
            if (r[0] == 0 && r[3] == 0) return;
            out.add(new RegionRow(region, (int) r[0], (int) r[1], (int) r[2], (int) r[3], (int) r[4], pct(r[4], r[2]),
                (int) r[5], (int) r[6], (int) r[7], pct(r[7], r[3]), (int) r[8], pct(r[8], r[3]), (int) r[9]));
        });
        out.sort(Comparator.comparingInt(RegionRow::agents).reversed().thenComparing(RegionRow::region));
        return out;
    }

    @Transactional(readOnly = true)
    public List<AgentVisitRow> agents(LocalDate date, String region) {
        MapSqlParameterSource p = new MapSqlParameterSource("date", date == null ? LocalDate.now(clock) : date);
        String byRegion = "";
        if (region != null && !region.isBlank()) { byRegion = " and a.region = :region"; p.addValue("region", region.trim()); }
        return jdbc.query("""
            select v.agent_id, a.name, a.region, count(*) as visits, count(*) filter (where not v.within_radius) as far,
                   count(*) filter (where v.in_plan = false) as out_plan, count(*) filter (where v.orders = 0) as no_order,
                   count(*) filter (where v.photos = 0) as no_photo, count(*) filter (where v.photos_suspect > 0) as old_photo,
                   coalesce(sum(v.photos), 0) as photos, count(pc.verdict) as ai_checked, count(*) filter (where pc.verdict = 'ok') as ai_ok
            """ + FROM + " where v.date = :date" + byRegion
            + " group by v.agent_id, a.name, a.region order by no_photo desc, old_photo desc, a.name", p,
            (rs, n) -> new AgentVisitRow(rs.getObject("agent_id", UUID.class), rs.getString("name"), rs.getString("region"),
                rs.getInt("visits"), rs.getInt("far"), rs.getInt("out_plan"), rs.getInt("no_order"), rs.getInt("no_photo"),
                rs.getInt("old_photo"), rs.getInt("photos"), rs.getInt("ai_checked"), rs.getInt("ai_ok")));
    }

    private static VisitRow row(ResultSet rs, int n) throws SQLException {
        Instant started = rs.getObject("started_at", OffsetDateTime.class).toInstant();
        OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
        Instant ended = endedAt == null ? null : endedAt.toInstant();
        String urls = rs.getString("photo_urls");
        return new VisitRow(rs.getObject("visit_id", UUID.class), rs.getObject("agent_id", UUID.class), rs.getString("agent_name"),
            rs.getString("region"), rs.getObject("date", LocalDate.class), started, ended,
            ended == null ? null : (int) Duration.between(started, ended).toMinutes(),
            rs.getString("outlet_ref"), rs.getString("outlet_name"), rs.getObject("distance_m", Integer.class), rs.getBoolean("within_radius"),
            rs.getObject("in_plan", Boolean.class), rs.getInt("photos"), rs.getInt("photos_suspect"),
            urls == null || urls.isBlank() ? List.of() : List.of(urls.split("\n")), rs.getInt("orders"), rs.getLong("orders_uzs"), photoAi(rs));
    }

    private static PhotoAi photoAi(ResultSet rs) throws SQLException {
        String verdict = rs.getString("pc_verdict");
        if (verdict == null) return null;
        Map<String, Integer> brands = new LinkedHashMap<>();
        String b = rs.getString("pc_brands");
        if (b != null) for (String part : b.split(",")) {
            int i = part.lastIndexOf(':');
            if (i > 0) try { brands.put(part.substring(0, i), Integer.parseInt(part.substring(i + 1))); } catch (NumberFormatException ignored) { }
        }
        String comp = rs.getString("pc_competitors");
        OffsetDateTime at = rs.getObject("pc_checked_at", OffsetDateTime.class);
        return new PhotoAi(verdict, rs.getString("pc_kind"), rs.getInt("pc_facings"), rs.getInt("pc_expected"), brands,
            comp == null ? List.of() : List.of(comp.split(",")), rs.getString("pc_note"), at == null ? null : at.toInstant());
    }

    private static int pct(long part, long whole) { return whole == 0 ? 0 : Math.round(100f * part / whole); }
    private static String escapeLike(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}
