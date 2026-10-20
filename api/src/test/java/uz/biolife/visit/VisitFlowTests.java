package uz.biolife.visit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import uz.biolife.ingest.application.FactPublisher;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.events.DomainEvents.DayPlanned;
import uz.biolife.shared.events.DomainEvents.SourceSynced;
import uz.biolife.shared.events.DomainEvents.VisitRecorded;
import uz.biolife.support.Facts;
import uz.biolife.support.TestDatabase;

/** Visit list filters and counts, branch coverage, per-agent checks, data-source freshness. */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisitFlowTests {
    @Autowired FactPublisher publisher;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    final LocalDate day = LocalDate.of(2026, 9, 15);
    final UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID(), v1 = UUID.randomUUID(), v2 = UUID.randomUUID(), v3 = UUID.randomUUID(), v4 = UUID.randomUUID();

    private VisitRecorded visit(UUID id, UUID agent, int hour, String store, Integer dist, boolean within, Boolean inPlan, int photos, int suspect, int orders, long uzs) {
        return visit(id, agent, hour, store, dist, within, inPlan, photos, suspect, orders, uzs, Instant.parse("2026-09-15T12:00:00Z"));
    }

    private VisitRecorded visit(UUID id, UUID agent, int hour, String store, Integer dist, boolean within, Boolean inPlan, int photos, int suspect, int orders, long uzs, Instant version) {
        Instant s = Facts.at(day, hour, 0);
        return new VisitRecorded(id, agent, day, s, s.plusSeconds(600), "m" + hour, store, dist, within, inPlan, photos, suspect,
            photos == 0 ? List.of() : List.of("https://biolife.ritm.uz/media/x" + hour + ".jpg", "http://insecure/should-drop.jpg"), orders, uzs, version);
    }

    @BeforeAll
    void seed() {
        publisher.publish(new AgentProfileChanged(a1, "Akmal Agent", "Самарканд", "Самарканд", true));
        publisher.publish(new AgentProfileChanged(a2, "Dilnoza Agent", "Андижон", "Андижон", true));
        publisher.publish(new DayPlanned(a1, day, 4));
        publisher.publish(new DayPlanned(a2, day, 2));
        publisher.publish(visit(v1, a1, 9, "Yulduz market", 12, true, true, 2, 0, 1, 250_000));
        publisher.publish(visit(v2, a1, 10, "Baraka do'koni", 820, false, true, 0, 0, 0, 0));
        publisher.publish(visit(v3, a1, 11, "Nur 50%_chegirma", 30, true, false, 1, 1, 0, 0));
        publisher.publish(visit(v4, a2, 12, "Chinor", 5, true, true, 3, 0, 2, 1_200_000));
        publisher.publish(new SourceSynced("bridge", "Kuzatuv ko'prigi", Instant.now(), true, "64 000 vizit"));
        // a newer version of v2 (the photo came in) and, arriving late, an even older one: the newest must win
        publisher.publish(visit(v2, a1, 10, "Baraka do'koni", 820, false, true, 1, 0, 0, 0, Instant.parse("2026-09-15T13:00:00Z")));
        publisher.publish(visit(v2, a1, 10, "Baraka do'koni", 820, false, true, 0, 0, 0, 0, Instant.parse("2026-09-15T11:00:00Z")));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
            jdbc.queryForObject("select count(*) from visit_record where date = ?", Integer.class, day) == 4
            && jdbc.queryForObject("select photos from visit_record where visit_id = ?", Integer.class, v2) == 1
            && jdbc.queryForObject("select count(*) from visit_plan_day where date = ?", Integer.class, day) == 2
            && jdbc.queryForObject("select count(*) from source_status", Integer.class) == 1);
    }

    private org.springframework.test.web.servlet.ResultActions asManager(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"));
    }

    @Test
    void listCountsEveryFilterAndPages() throws Exception {
        asManager(get("/api/v1/panel/visits").param("date", day.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.counts.far").value(1))
            .andExpect(jsonPath("$.counts.outplan").value(1))
            .andExpect(jsonPath("$.counts.noorder").value(2))
            .andExpect(jsonPath("$.counts.nophoto").value(0))
            .andExpect(jsonPath("$.counts.withphoto").value(4))
            .andExpect(jsonPath("$.counts.oldphoto").value(1))
            .andExpect(jsonPath("$.items[0].outletName").value("Chinor"))
            .andExpect(jsonPath("$.items[0].photoUrls.length()").value(1))
            .andExpect(jsonPath("$.items[0].minutes").value(10))
            .andExpect(jsonPath("$.regions.length()").value(2));
        asManager(get("/api/v1/panel/visits").param("date", day.toString()).param("kind", "far"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].distanceM").value(820)).andExpect(jsonPath("$.items[0].agentName").value("Akmal Agent"));
        asManager(get("/api/v1/panel/visits").param("date", day.toString()).param("region", "Андижон"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].ordersUzs").value(1_200_000));
        asManager(get("/api/v1/panel/visits").param("date", day.toString()).param("q", "50%_"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].inPlan").value(false));
        asManager(get("/api/v1/panel/visits").param("date", day.toString()).param("size", "2").param("page", "1"))
            .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[1].outletName").value("Yulduz market"));
    }

    @Test
    void branchCoverageAndAgentChecks() throws Exception {
        asManager(get("/api/v1/panel/visits/regions").param("from", day.toString()).param("to", day.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.region == 'Самарканд')].planned").value(4))
            .andExpect(jsonPath("$[?(@.region == 'Самарканд')].visits").value(3))
            .andExpect(jsonPath("$[?(@.region == 'Самарканд')].inPlanVisits").value(2))
            .andExpect(jsonPath("$[?(@.region == 'Самарканд')].coveragePct").value(50))
            .andExpect(jsonPath("$[?(@.region == 'Самарканд')].farVisits").value(1))
            .andExpect(jsonPath("$[?(@.region == 'Андижон')].orderRatePct").value(100));
        asManager(get("/api/v1/panel/visits/agents").param("date", day.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name == 'Akmal Agent')].visits").value(3))
            .andExpect(jsonPath("$[?(@.name == 'Akmal Agent')].oldPhoto").value(1));
    }

    @Test
    void sourcesAndAccess() throws Exception {
        asManager(get("/api/v1/panel/sources"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].source").value("bridge")).andExpect(jsonPath("$[0].ok").value(true))
            .andExpect(jsonPath("$[0].ageMinutes").value(0));
        mvc.perform(get("/api/v1/panel/visits").header("X-User-Id", a1.toString()).header("X-User-Role", "AGENT")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/panel/visits/regions").param("from", "2026-09-20").param("to", "2026-09-01")
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/panel/visits").param("date", "not-a-date")
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/panel/no-such-thing").header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isNotFound());
    }
}
