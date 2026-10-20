package uz.biolife.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import uz.biolife.ingest.application.FactPublisher;
import uz.biolife.reporting.application.ReportService;
import uz.biolife.reporting.domain.AgentDay;
import uz.biolife.reporting.domain.ReportDelivery;
import uz.biolife.reporting.infra.AgentDayRepository;
import uz.biolife.shared.time.WorkHours;
import uz.biolife.support.Facts;
import uz.biolife.support.TestDatabase;

/** Work-hours discipline, per-agent KPIs, overview tiles and report delivery. */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportingFlowTests {
    @Autowired FactPublisher publisher;
    @Autowired AgentDayRepository days;
    @Autowired ReportService reports;
    @Autowired MockMvc mvc;

    final LocalDate monday = WorkHours.weekStart(LocalDate.of(2026, 9, 7));
    UUID early, late;

    @BeforeAll
    void seed() {
        Facts f = new Facts(publisher);
        early = f.agent("Nilufar X.", "Buxoro");
        late = f.agent("Jasur K.", "Toshkent");
        f.planned(early, monday, 8);
        f.planned(late, monday, 8);
        // Nilufar: 09:05 → 17:50, 3 visits, all good
        f.visit(early, monday, Facts.at(monday, 9, 5), 88, Facts.criteria(10, 16, 18, 15, 8, 12, 9), true, List.of(), 20);
        f.visit(early, monday, Facts.at(monday, 13, 0), 80, Facts.criteria(10, 14, 16, 15, 8, 10, 7), true, List.of(), 12);
        f.visit(early, monday, Facts.at(monday, 17, 35), 90, Facts.criteria(10, 18, 18, 15, 9, 12, 8), true, List.of(), 25);
        // Jasur: 10:40 → 15:10, late start and early finish, one excused
        f.visit(late, monday, Facts.at(monday, 10, 40), 52, Facts.criteria(6, 6, 10, 5, 5, 10, 10), false, List.of(), 8);
        f.excused(late, monday, Facts.at(monday, 14, 55));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
            days.findByAgentIdAndDate(late, monday).map(d -> d.getExcused() == 1 && d.getScoreCount() == 1).orElse(false)
            && days.findByAgentIdAndDate(early, monday).map(d -> d.getScoreCount() == 3 && d.getPhotosTotal() == 3).orElse(false));
    }

    @Test
    void workHoursAreDerivedFromFirstAndLastActivity() {
        AgentDay n = days.findByAgentIdAndDate(early, monday).orElseThrow();
        assertThat(n.isLateStart()).isFalse();
        assertThat(n.isEarlyFinish()).isFalse();
        assertThat(n.getActiveMinutes()).isBetween(8 * 60 + 40, 8 * 60 + 50);
        AgentDay j = days.findByAgentIdAndDate(late, monday).orElseThrow();
        assertThat(j.isLateStart()).isTrue();
        assertThat(j.isEarlyFinish()).isTrue();
        assertThat(j.getClosed()).isEqualTo(1);
        assertThat(j.getExcused()).isEqualTo(1);
    }

    @Test
    void overviewAndAgentKpisForManager() throws Exception {
        mvc.perform(get("/api/v1/panel/analytics/overview").param("date", monday.toString())
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentsActive").value(2))
            .andExpect(jsonPath("$.planned").value(16))
            .andExpect(jsonPath("$.closed").value(4))
            .andExpect(jsonPath("$.excused").value(1))
            .andExpect(jsonPath("$.lateStarts").value(1))
            .andExpect(jsonPath("$.earlyFinishes").value(1))
            .andExpect(jsonPath("$.avgScore").value(78));

        mvc.perform(get("/api/v1/panel/analytics/agents").param("from", monday.toString()).param("to", monday.plusDays(5).toString())
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Nilufar X."))
            .andExpect(jsonPath("$[0].avgScore").value(86))
            .andExpect(jsonPath("$[0].promoRate").value(100))
            .andExpect(jsonPath("$[1].completionRate").value(13))
            .andExpect(jsonPath("$[1].lateStarts").value(1));

        mvc.perform(get("/api/v1/panel/analytics/work-hours").param("from", monday.toString()).param("to", monday.toString())
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Jasur K."))
            .andExpect(jsonPath("$[0].avgFirstActivity").value("10:40"));
    }

    @Test
    void agentSeesOwnSeriesButNotOthers() throws Exception {
        mvc.perform(get("/api/v1/panel/analytics/agents/{id}", early).param("from", monday.toString()).param("to", monday.toString())
                .header("X-User-Id", early.toString()).header("X-User-Role", "AGENT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.days[0].closed").value(3));
        mvc.perform(get("/api/v1/panel/analytics/agents/{id}", early)
                .header("X-User-Id", late.toString()).header("X-User-Role", "AGENT"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/panel/analytics/overview")
                .header("X-User-Id", late.toString()).header("X-User-Role", "AGENT"))
            .andExpect(status().isForbidden());
    }

    @Test
    void dailyReportIsComposedSentAndRecorded() throws Exception {
        ReportDelivery d = reports.sendDaily(monday);
        assertThat(d.getStatus()).isEqualTo("SENT");
        assertThat(d.getChannel()).isEqualTo("log");
        assertThat(d.getBody()).contains("Kunlik hisobot · 7-sentabr, dushanba").contains("Vizitlar (bajarildi / reja): 4 / 16")
            .contains("Ishni kech boshlagan (09:30 dan keyin): 1").contains("Savdo bo'yicha eng yaxshi");

        mvc.perform(post("/api/v1/panel/reports/send").param("type", "weekly").param("date", monday.toString())
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("WEEKLY"))
            .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Nilufar X.")));

        mvc.perform(get("/api/v1/panel/reports").header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "MANAGER"))
            .andExpect(status().isOk())   // other test classes share the database: check only that both deliveries are listed
            .andExpect(jsonPath("$[?(@.type == 'DAILY' && @.periodStart == '" + monday + "')]").exists())
            .andExpect(jsonPath("$[?(@.type == 'WEEKLY' && @.periodStart == '" + monday + "')]").exists());
    }
}
