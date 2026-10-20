package uz.biolife.conversation.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.biolife.conversation.application.ConversationDtos.AgentRecording;
import uz.biolife.conversation.application.ConversationDtos.CriterionScore;
import uz.biolife.conversation.application.ConversationDtos.Detail;
import uz.biolife.conversation.application.ConversationDtos.Page;
import uz.biolife.conversation.application.ConversationDtos.Row;
import uz.biolife.conversation.application.ConversationDtos.Stats;
import uz.biolife.conversation.domain.Recording;
import uz.biolife.conversation.infra.ConvAgentRepository;
import uz.biolife.conversation.infra.RecordingRepository;
import uz.biolife.shared.web.NotFoundException;

/** Read side: the agent's own recordings, the manager's conversation list, one conversation in full, day statistics. */
@Service
public class ConversationQueries {
    /** A scored conversation: analysed and not flagged as having nothing to judge (see ConversationService.NOT_ASSESSABLE). */
    static final String ASSESSABLE = "coalesce(r.flags, '') !~ '(^|,)(no_speech|unclear_audio)(,|$)'";
    static final Map<String, String> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("all", "true");
        KINDS.put("pending", "r.status in ('QUEUED', 'WORKING')");
        KINDS.put("low", "r.status = 'DONE' and r.score < 60 and " + ASSESSABLE);
        KINDS.put("best", "r.status = 'DONE' and r.score >= 90 and coalesce(r.flags, '') = ''");
        KINDS.put("flagged", "coalesce(r.flags, '') <> ''");
        KINDS.put("failed", "r.status = 'FAILED'");
    }
    private static final String FROM = " from conv_recording r left join conv_agent a on a.id = r.agent_id";
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final RecordingRepository recordings;
    private final ConvAgentRepository agents;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ConversationQueries(NamedParameterJdbcTemplate jdbc, RecordingRepository recordings, ConvAgentRepository agents, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.recordings = recordings; this.agents = agents; this.mapper = mapper; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AgentRecording> mine(UUID agentId, LocalDate date) {
        return recordings.findByAgentIdAndDateOrderByStartedAtDesc(agentId, date == null ? LocalDate.now(clock) : date).stream().map(this::forAgent).toList();
    }

    public AgentRecording forAgent(Recording r) {
        return new AgentRecording(r.getId(), r.getStartedAt(), r.getDurationSec(), r.getStatus().name(), r.getScore(), r.getOutletName(),
            r.getSummary(), r.getAdvice(), criteria(r), ConversationService.flags(r));
    }

    @Transactional(readOnly = true)
    public Page list(LocalDate date, String kind, String region, String q, UUID agentId, int page, int size) {
        LocalDate d = date == null ? LocalDate.now(clock) : date;
        String k = kind != null && KINDS.containsKey(kind) ? kind : "all";
        int sz = Math.max(1, Math.min(200, size)), pg = Math.max(0, page);
        MapSqlParameterSource p = new MapSqlParameterSource("date", d);
        StringBuilder where = new StringBuilder(" where r.date = :date");
        if (region != null && !region.isBlank()) { where.append(" and a.region = :region"); p.addValue("region", region.trim()); }
        if (agentId != null) { where.append(" and r.agent_id = :agentId"); p.addValue("agentId", agentId); }
        if (q != null && !q.isBlank()) {
            where.append(" and (a.name ilike :q or r.outlet_name ilike :q)");
            p.addValue("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
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
        List<Row> items = jdbc.query("select r.*, a.name as agent_name, a.region as region" + FROM + where + " and (" + KINDS.get(k)
            + ") order by r.started_at desc, r.id limit :limit offset :offset", p, ConversationQueries::row);
        List<String> regions = jdbc.queryForList("select distinct region from conv_agent where active and region is not null order by region",
            new MapSqlParameterSource(), String.class);
        return new Page(d, k, pg, sz, counts == null ? 0 : counts.get(k), counts, regions, items);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        Recording r = recordings.findById(id).orElseThrow(() -> new NotFoundException("Suhbat topilmadi"));
        var a = agents.findById(r.getAgentId());
        Row row = new Row(r.getId(), r.getAgentId(), a.map(x -> x.getName()).orElse(null), a.map(x -> x.getRegion()).orElse(null), r.getDate(),
            r.getStartedAt(), r.getDurationSec(), r.getStatus().name(), r.getScore(), r.getOutletName(), ConversationService.flags(r), r.getSummary());
        Map<String, Object> analysis = r.getAnalysis() == null ? Map.of() : mapper.readValue(r.getAnalysis(), MAP);
        List<Map<String, Object>> transcript = r.getTranscript() == null ? List.of() : mapper.readValue(r.getTranscript(), LIST);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = analysis.get("evidence") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> models = analysis.get("models") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return new Detail(row, r.getError(), r.getAttempts(), transcript, criteria(r), evidence, r.getAdvice(),
            analysis.get("agentSpeaker") == null ? null : String.valueOf(analysis.get("agentSpeaker")),
            analysis.get("language") == null ? null : String.valueOf(analysis.get("language")), r.getPromoMentioned(), r.getVisitId(), models);
    }

    @Transactional(readOnly = true)
    public Stats stats(LocalDate date) {
        LocalDate d = date == null ? LocalDate.now(clock) : date;
        return jdbc.queryForObject("""
            select count(*) as recorded, count(*) filter (where r.status = 'DONE') as analysed,
                   count(*) filter (where r.status in ('QUEUED', 'WORKING')) as pending, count(*) filter (where r.status = 'FAILED') as failed,
                          round(avg(r.score) filter (where r.status = 'DONE' and %s)) as avg_score, count(distinct r.agent_id) as agents
            from conv_recording r where r.date = :date""".formatted(ASSESSABLE), new MapSqlParameterSource("date", d),
            (rs, n) -> new Stats(d, rs.getLong("recorded"), rs.getLong("analysed"), rs.getLong("pending"), rs.getLong("failed"),
                rs.getObject("avg_score") == null ? null : rs.getInt("avg_score"), rs.getLong("agents")));
    }

    List<CriterionScore> criteria(Recording r) {
        if (r.getAnalysis() == null) return List.of();
        Object c = mapper.readValue(r.getAnalysis(), MAP).get("criteria");
        List<CriterionScore> out = new ArrayList<>();
        if (c instanceof Map<?, ?> m) {
            for (Rubric.Item item : Rubric.ITEMS) {
                Object v = m.get(item.code());
                out.add(new CriterionScore(item.code(), item.title(), v instanceof Number n ? n.intValue() : 0, item.weight()));
            }
        }
        return out;
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        String flags = rs.getString("flags");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("agent_id", UUID.class), rs.getString("agent_name"), rs.getString("region"),
            rs.getObject("date", LocalDate.class), rs.getObject("started_at", OffsetDateTime.class).toInstant(), rs.getInt("duration_sec"),
            rs.getString("status"), rs.getObject("score", Integer.class), rs.getString("outlet_name"),
            flags == null || flags.isBlank() ? List.of() : List.of(flags.split(",")), rs.getString("summary"));
    }
}
