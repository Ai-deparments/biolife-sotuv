package uz.biolife.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import uz.biolife.ingest.application.FactPublisher;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.events.DomainEvents.VisitRecorded;
import uz.biolife.support.Facts;
import uz.biolife.support.TestDatabase;
import uz.biolife.visit.application.PhotoCheckService;

/**
 * Shelf-photo AI: the worker claims visits with photos (fair share per agent, newest first), reports what it saw; the API
 * computes the verdict against the standard, shows it in the panel and publishes PhotoChecked to reporting. Plus retries and access.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoCheckFlowTests {
    @Autowired FactPublisher publisher;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    final UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID();
    final UUID v1 = UUID.randomUUID(), v2 = UUID.randomUUID(), v3 = UUID.randomUUID(), v4 = UUID.randomUUID(), v5 = UUID.randomUUID();
    LocalDate today;

    private VisitRecorded visit(UUID id, UUID agent, int hour, String store, int photos) {
        Instant s = Facts.at(today, hour, 0);
        return new VisitRecorded(id, agent, today, s, s.plusSeconds(600), "p" + hour, store, 10, true, true, photos, 0,
            photos == 0 ? List.of() : List.of("https://biolife.ritm.uz/media/shelf-" + hour + ".jpg"), 0, 0, Instant.now());
    }

    @BeforeAll
    void seed() {
        today = LocalDate.now(clock);
        publisher.publish(new AgentProfileChanged(a1, "Rasm Akmal", "Buxoro", "Buxoro", true));
        publisher.publish(new AgentProfileChanged(a2, "Rasm Dilnoza", "Xorazm", "Xorazm", true));
        publisher.publish(visit(v1, a1, 9, "Ixlos market", 1));
        publisher.publish(visit(v2, a1, 10, "Baraka", 2));
        publisher.publish(visit(v3, a1, 11, "Nur", 1));
        publisher.publish(visit(v4, a2, 12, "Chinor", 1));
        publisher.publish(visit(v5, a2, 13, "Rasmsiz do'kon", 0));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
            jdbc.queryForObject("select count(*) from visit_record where visit_id in (?, ?, ?, ?, ?)", Integer.class, v1, v2, v3, v4, v5) == 5
            && jdbc.queryForObject("select count(*) from visit_agent where id in (?, ?)", Integer.class, a1, a2) == 2);
    }

    private ResultActions as(AbstractMockHttpServletRequestBuilder<?> r, String role) throws Exception {
        return mvc.perform(r.header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", role));
    }

    private ResultActions result(UUID visit, String json) throws Exception {
        return as(post("/api/v1/internal/photos/{id}/result", visit).contentType(MediaType.APPLICATION_JSON).content(json), "ADMIN");
    }

    private String verdictOf(UUID visit) {
        return jdbc.queryForObject("select verdict from visit_photo_check where visit_id = ?", String.class, visit);
    }

    @Test
    void verdictFollowsTheStandard() {
        assertThat(PhotoCheckService.verdict("shelf", true, 7, 6)).isEqualTo("ok");
        assertThat(PhotoCheckService.verdict("shelf", true, 3, 6)).isEqualTo("low");
        assertThat(PhotoCheckService.verdict("shelf", true, 0, 1)).isEqualTo("none");
        assertThat(PhotoCheckService.verdict("shelf", false, 9, 1)).isEqualTo("unclear");
        assertThat(PhotoCheckService.verdict("outside", true, 9, 1)).isEqualTo("not_shelf");
        assertThat(PhotoCheckService.verdict("shelf", true, 1, 0)).isEqualTo("ok");
    }

    @Test
    void photosAreClaimedFairlyCheckedShownAndReported() throws Exception {
        // drain the queue (other tests may have visits today too); order across claims is the global order
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String body = as(post("/api/v1/internal/photos/claim").param("limit", "3"), "ADMIN").andReturn().getResponse().getContentAsString();
            if (body.isEmpty()) break;
            assertThat(JsonPath.<Integer>read(body, "$.expected")).isEqualTo(1);
            order.addAll(JsonPath.read(body, "$.items[*].visitId"));
            if (body.contains(v3.toString())) {
                assertThat(body).contains("Rasm Akmal").contains("https://biolife.ritm.uz/media/shelf-11.jpg");
            }
        }
        assertThat(order).contains(v1.toString(), v2.toString(), v3.toString(), v4.toString()).doesNotContain(v5.toString());
        // newest visit of each agent first, then the second visit of the agent who has more
        assertThat(order.indexOf(v3.toString())).isLessThan(order.indexOf(v2.toString()));
        assertThat(order.indexOf(v4.toString())).isLessThan(order.indexOf(v2.toString()));
        assertThat(order.indexOf(v2.toString())).isLessThan(order.indexOf(v1.toString()));
        as(post("/api/v1/internal/photos/claim"), "ADMIN").andExpect(status().isNoContent());   // everything is leased

        result(v3, """
            {"kind":"shelf","clear":true,"facings":5,"brands":{"FRUCTIS":3,"Time Tea":2,"Pepsi":4},
             "competitors":["Coca-Cola","Fanta"],"note":"FRUCTIS va Time Tea bor","photosSeen":1,"model":"claude-sonnet"}""")
            .andExpect(status().isNoContent());
        assertThat(verdictOf(v3)).isEqualTo("ok");
        assertThat(jdbc.queryForObject("select brands from visit_photo_check where visit_id = ?", String.class, v3)).isEqualTo("FRUCTIS:3,Time Tea:2");
        result(v3, "{\"kind\":\"shelf\",\"clear\":true,\"facings\":0}").andExpect(status().isNoContent());   // already done: unchanged
        assertThat(verdictOf(v3)).isEqualTo("ok");

        as(put("/api/v1/panel/visits/photo-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedFacings\":6,\"standardText\":\"Kamida 6 ta yuz, ko'z darajasida\"}"), "MANAGER")
            .andExpect(status().isOk()).andExpect(jsonPath("$.expectedFacings").value(6));
        as(put("/api/v1/panel/visits/photo-settings").contentType(MediaType.APPLICATION_JSON).content("{\"expectedFacings\":0}"), "MANAGER")
            .andExpect(status().isUnprocessableContent());
        as(get("/api/v1/panel/visits/photo-settings"), "MANAGER").andExpect(status().isOk())
            .andExpect(jsonPath("$.expectedFacings").value(6)).andExpect(jsonPath("$.standardText").value("Kamida 6 ta yuz, ko'z darajasida"));

        result(v2, "{\"kind\":\"shelf\",\"clear\":true,\"facings\":3,\"brands\":{\"BIOLIFE\":3}}").andExpect(status().isNoContent());
        result(v1, "{\"kind\":\"outside\",\"clear\":true,\"facings\":0,\"note\":\"Do'konning tashqi ko'rinishi\"}").andExpect(status().isNoContent());
        assertThat(verdictOf(v2)).isEqualTo("low");
        assertThat(verdictOf(v1)).isEqualTo("not_shelf");

        // a retryable failure waits (not claimable now); a final one gives up
        as(post("/api/v1/internal/photos/{id}/fail", v4).contentType(MediaType.APPLICATION_JSON)
            .content("{\"error\":\"rasm yuklanmadi\",\"retryable\":true,\"delaySec\":600}"), "ADMIN").andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status from visit_photo_check where visit_id = ?", String.class, v4)).isEqualTo("leased");
        as(post("/api/v1/internal/photos/claim"), "ADMIN").andExpect(status().isNoContent());
        as(post("/api/v1/internal/photos/{id}/fail", v4).contentType(MediaType.APPLICATION_JSON)
            .content("{\"error\":\"rasm manbada yo'q (404)\",\"retryable\":false}"), "ADMIN").andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status from visit_photo_check where visit_id = ?", String.class, v4)).isEqualTo("failed");

        as(get("/api/v1/panel/visits").param("date", today.toString()).param("agentId", a1.toString()).param("kind", "photook"), "MANAGER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.counts.withphoto").value(3))
            .andExpect(jsonPath("$.counts.photobad").value(2))
            .andExpect(jsonPath("$.items[0].photoAi.verdict").value("ok"))
            .andExpect(jsonPath("$.items[0].photoAi.facings").value(5))
            .andExpect(jsonPath("$.items[0].photoAi.expected").value(1))
            .andExpect(jsonPath("$.items[0].photoAi.brands.FRUCTIS").value(3))
            .andExpect(jsonPath("$.items[0].photoAi.competitors[0]").value("Coca-Cola"));
        as(get("/api/v1/panel/visits").param("date", today.toString()).param("agentId", a2.toString()), "MANAGER")
            .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.counts.photook").value(0)).andExpect(jsonPath("$.counts.photobad").value(0));
        as(get("/api/v1/panel/visits/agents").param("date", today.toString()).param("region", "Buxoro"), "MANAGER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name == 'Rasm Akmal')].aiChecked").value(3))
            .andExpect(jsonPath("$[?(@.name == 'Rasm Akmal')].aiOk").value(1));

        // PhotoChecked reaches reporting: 3 checked photos, 1 met the standard
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
            jdbc.queryForObject("select coalesce(sum(photos_total), 0) from report_agent_day where agent_id = ?", Integer.class, a1) == 3
            && jdbc.queryForObject("select coalesce(sum(photos_ok), 0) from report_agent_day where agent_id = ?", Integer.class, a1) == 1);

        as(put("/api/v1/panel/visits/photo-settings").contentType(MediaType.APPLICATION_JSON).content("{\"expectedFacings\":1}"), "MANAGER")
            .andExpect(status().isOk());
    }

    @Test
    void onlyTheServiceTalksToTheQueue() throws Exception {
        as(post("/api/v1/internal/photos/claim"), "AGENT").andExpect(status().isForbidden());
        as(post("/api/v1/internal/photos/claim"), "MANAGER").andExpect(status().isForbidden());
        result(UUID.randomUUID(), "{\"kind\":\"shelf\",\"facings\":2}").andExpect(status().isNotFound());
        as(get("/api/v1/panel/visits/photo-settings"), "AGENT").andExpect(status().isForbidden());
    }
}
