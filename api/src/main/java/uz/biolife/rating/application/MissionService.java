package uz.biolife.rating.application;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.biolife.rating.domain.Criteria;
import uz.biolife.rating.domain.Mission;
import uz.biolife.rating.domain.VisitFact;
import uz.biolife.rating.infra.MissionRepository;

/** Builds the weekly mission from the criterion with the lowest fulfilment ratio. */
@Service
public class MissionService {
    private static final TypeReference<Map<String, Integer>> MAP = new TypeReference<>() {};
    private final MissionRepository missions;
    private final ObjectMapper mapper;

    public MissionService(MissionRepository missions, ObjectMapper mapper) { this.missions = missions; this.mapper = mapper; }

    /** Average points per criterion across the agent's analysed conversations. */
    public Map<String, Integer> averageCriteria(List<VisitFact> facts) {
        Map<String, Integer> sum = new HashMap<>();
        int n = 0;
        for (VisitFact f : facts) {
            if (f.getCriteriaJson() == null) continue;
            n++;
            parse(f.getCriteriaJson()).forEach((k, v) -> sum.merge(k, v, Integer::sum));
        }
        Map<String, Integer> avg = new HashMap<>();
        if (n == 0) return avg;
        final int count = n;
        sum.forEach((k, v) -> avg.put(k, Math.round((float) v / count)));
        return avg;
    }

    public Mission refresh(UUID agentId, LocalDate weekStart, Map<String, Integer> avgCriteria) {
        Criteria.Def weakest = null;
        double worst = Double.MAX_VALUE;
        for (Criteria.Def d : Criteria.all().values()) {
            double ratio = (double) avgCriteria.getOrDefault(d.code(), 0) / d.weight();
            if (ratio < worst) { worst = ratio; weakest = d; }
        }
        if (weakest == null) return null;
        int current = avgCriteria.getOrDefault(weakest.code(), 0);
        String text = weakest.title() + ": " + current + " / " + weakest.weight() + ". " + weakest.advice();
        final Criteria.Def w = weakest;
        Mission m = missions.findByAgentIdAndWeekStart(agentId, weekStart)
            .map(x -> { x.refresh(w.code(), current, w.weight(), text); return x; })
            .orElseGet(() -> new Mission(agentId, weekStart, w.code(), current, w.weight(), text));
        return missions.save(m);
    }

    private Map<String, Integer> parse(String json) {
        try { return mapper.readValue(json, MAP); } catch (Exception e) { return Map.of(); }
    }
}
