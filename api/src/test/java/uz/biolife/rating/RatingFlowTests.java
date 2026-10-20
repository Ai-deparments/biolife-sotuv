package uz.biolife.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uz.biolife.ingest.application.FactPublisher;
import uz.biolife.rating.application.WeeklyRatingService;
import uz.biolife.rating.domain.WeeklyRating;
import uz.biolife.rating.infra.VisitFactRepository;
import uz.biolife.shared.time.WorkHours;
import uz.biolife.support.Facts;
import uz.biolife.support.TestDatabase;

/** End-to-end: events → facts → weekly rating → API, including the privacy rule of the public card. */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RatingFlowTests {
    @Autowired FactPublisher publisher;
    @Autowired VisitFactRepository facts;
    @Autowired WeeklyRatingService weekly;
    @Autowired MockMvc mvc;

    final LocalDate week = WorkHours.weekStart(LocalDate.of(2026, 10, 5));
    UUID dilnoza, sardor, olim;

    @BeforeAll
    void seed() {
        Facts f = new Facts(publisher);
        dilnoza = f.agent("Dilnoza R.", "Andijon");
        sardor = f.agent("Sardor T.", "Toshkent");
        olim = f.agent("Olim B.", "Xorazm");
        // Dilnoza: 6 days of 90+ → streak, best case, conversation bonus
        for (int i = 0; i < 6; i++) {
            LocalDate d = week.plusDays(i);
            f.visit(dilnoza, d, Facts.at(d, 9, 10), 94, Facts.criteria(10, 19, 19, 15, 9, 14, 8), true, List.of(), 40);
        }
        // Sardor: average, one day without promo
        f.visit(sardor, week, Facts.at(week, 9, 20), 82, Facts.criteria(10, 8, 16, 15, 10, 13, 10), true, List.of(), 20);
        f.visit(sardor, week.plusDays(1), Facts.at(week.plusDays(1), 9, 48), 58, Facts.criteria(8, 4, 14, 0, 10, 12, 10), false, List.of(), 8);
        f.excused(sardor, week.plusDays(2), Facts.at(week.plusDays(2), 10, 30));
        // Olim: low and flagged → no bonus, falling
        f.visit(olim, week, Facts.at(week, 10, 35), 0, Facts.criteria(0, 0, 0, 0, 0, 0, 0), false, List.of("single_voice"), 0);
        f.visit(olim, week.plusDays(1), Facts.at(week.plusDays(1), 10, 40), 41, Facts.criteria(5, 5, 8, 5, 5, 8, 5), false, List.of(), 6);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> facts.count() >= 11);
    }

    @Test
    void computesRankTrendStreakAndBonuses() {
        // the Spring context (and DB) is shared with other test classes, so filter to this test's agents
        List<WeeklyRating> rows = weekly.compute(week).stream()
            .filter(r -> List.of(dilnoza, sardor, olim).contains(r.getAgentId())).toList();
        assertThat(rows).hasSize(3);
        WeeklyRating top = rows.get(0);
        assertThat(top.getAgentId()).isEqualTo(dilnoza);
        assertThat(top.getRank()).isEqualTo(1);
        assertThat(top.getAvgScore()).isEqualTo(94);
        assertThat(top.getStreakDays()).isEqualTo(6);
        assertThat(top.getConversationBonusUzs()).isEqualTo(380_000);
        assertThat(top.getSalesBonusUzs()).isEqualTo(6 * 40 * 48_000L * 150 / 10_000);

        WeeklyRating mid = rows.get(1);
        assertThat(mid.getAgentId()).isEqualTo(sardor);
        assertThat(mid.getAvgScore()).isEqualTo(70);
        assertThat(mid.getPromoRate()).isEqualTo(50);
        assertThat(mid.getConversationBonusUzs()).isZero();
        assertThat(mid.getBonusReason()).contains("o'rtacha ball 70 < 80");

        WeeklyRating low = rows.get(2);
        assertThat(low.getAgentId()).isEqualTo(olim);
        assertThat(low.getBonusReason()).contains("shubhali yozuv bor");
    }

    @Test
    void meRatingShowsBreakdownMissionAndTop() throws Exception {
        mvc.perform(get("/api/v1/me/rating").param("week", week.toString())
                .header("X-User-Id", sardor.toString()).header("X-User-Role", "AGENT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Sardor T."))
            .andExpect(jsonPath("$.rank").value(2))
            .andExpect(jsonPath("$.totalAgents").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
            .andExpect(jsonPath("$.criteria.length()").value(7))
            .andExpect(jsonPath("$.mission.criterionCode").value("ehtiyoj"))
            .andExpect(jsonPath("$.top[0].name").value("Dilnoza R."));
    }

    @Test
    void publicCardHidesMoneyAndBreakdownAndShowsOnlyPublishedCases() throws Exception {
        String pending = mvc.perform(get("/api/v1/panel/best-cases").param("status", "pending")
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].agentId").value(dilnoza.toString()))
            .andExpect(jsonPath("$[?(@.agentId == '" + dilnoza + "')]", org.hamcrest.Matchers.hasSize(1)))
            .andReturn().getResponse().getContentAsString();
        String caseId = pending.replaceAll(".*?\"id\":\"([0-9a-f-]{36})\".*", "$1");

        mvc.perform(get("/api/v1/rating/agents/{id}", dilnoza).param("week", week.toString())
                .header("X-User-Id", sardor.toString()).header("X-User-Role", "AGENT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rank").value(1))
            .andExpect(jsonPath("$.bestCases.length()").value(0))
            .andExpect(jsonPath("$.salesBonusUzs").doesNotExist())
            .andExpect(jsonPath("$.criteria").doesNotExist());

        mvc.perform(post("/api/v1/panel/best-cases/{id}/publish", caseId)
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.published").value(true));

        mvc.perform(get("/api/v1/rating/agents/{id}", dilnoza).param("week", week.toString())
                .header("X-User-Id", sardor.toString()).header("X-User-Role", "AGENT"))
            .andExpect(jsonPath("$.bestCases.length()").value(1));
    }

    @Test
    void attentionListsEncourageAndFalling() throws Exception {
        mvc.perform(get("/api/v1/panel/rating/attention").param("week", week.toString())
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.encourage[0].name").value("Dilnoza R."))
            .andExpect(jsonPath("$.falling[0].name").value("Olim B."));
    }

    @Test
    void rulesAreValidatedAndManagerOnly() throws Exception {
        String body = "{\"salesPercentBp\":200,\"conversationMinScore\":85,\"conversationMinProofsRate\":90,\"conversationBonusUzs\":400000,\"streakThreshold\":80,\"fallingBelow\":50}";
        mvc.perform(put("/api/v1/panel/rating/rules").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-User-Id", sardor.toString()).header("X-User-Role", "AGENT"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/panel/rating/rules").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.salesPercentBp").value(200));
        mvc.perform(put("/api/v1/panel/rating/rules").contentType(MediaType.APPLICATION_JSON).content(body.replace("\"conversationMinScore\":85", "\"conversationMinScore\":140"))
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    void missingIdentityHeadersGive401() throws Exception {
        mvc.perform(get("/api/v1/me/rating")).andExpect(status().isUnauthorized());
    }
}
