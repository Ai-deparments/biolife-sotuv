package uz.biolife.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import uz.biolife.ingest.application.FactPublisher;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.events.DomainEvents.VisitRecorded;
import uz.biolife.shared.time.WorkHours;
import uz.biolife.support.TestDatabase;

/**
 * Recording → worker (claim, result) → linked to the Linko visit by time → AnalysisCompleted → rating and reporting.
 * Plus permissions, idempotent upload, retry with delay, panel list/detail/audio, analyst settings.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationFlowTests {
    @Autowired FactPublisher publisher;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    final UUID agent = UUID.randomUUID(), other = UUID.randomUUID(), visit = UUID.randomUUID();
    final Instant visitStart = Instant.now().minus(Duration.ofMinutes(40));
    final LocalDate day = uz.biolife.shared.time.WorkHours.dateOf(visitStart);
    final byte[] audio = "OggS-fake-audio-bytes".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    void seed() {
        publisher.publish(new AgentProfileChanged(agent, "Suhbat Agent", "Самарканд", "Самарканд", true));
        publisher.publish(new VisitRecorded(visit, agent, day, visitStart, visitStart.plusSeconds(600), "m1", "Yulduz market", 15, true, true,
            1, 0, List.of(), 1, 500_000, Instant.now()));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
            jdbc.queryForObject("select count(*) from conv_visit where visit_id = ?", Integer.class, visit) == 1
            && jdbc.queryForObject("select count(*) from conv_agent where id = ?", Integer.class, agent) == 1);
    }

    private ResultActions as(AbstractMockHttpServletRequestBuilder<?> r, UUID who, String role) throws Exception {
        return mvc.perform(r.header("X-User-Id", who.toString()).header("X-User-Role", role));
    }

    private ResultActions upload(UUID id, UUID who, String role, Instant start, int seconds) throws Exception {
        return as(multipart("/api/v1/me/recordings").file(new MockMultipartFile("file", "a.ogg", "audio/ogg;codecs=opus", audio))
            .param("id", id.toString()).param("startedAt", start.toString()).param("endedAt", start.plusSeconds(seconds).toString())
            .param("durationSec", String.valueOf(seconds)), who, role);
    }

    private String claim() throws Exception {
        return as(post("/api/v1/internal/recordings/claim"), UUID.randomUUID(), "ADMIN").andReturn().getResponse().getContentAsString();
    }

    @Test
    void recordingIsAnalysedLinkedToTheVisitAndCountsInRating() throws Exception {
        UUID rec = UUID.randomUUID();
        upload(rec, agent, "AGENT", visitStart.plusSeconds(60), 240).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED")).andExpect(jsonPath("$.outletName").value("Yulduz market"));
        upload(rec, agent, "AGENT", visitStart.plusSeconds(60), 240).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(rec.toString()));
        upload(UUID.randomUUID(), agent, "MANAGER", visitStart, 60).andExpect(status().isForbidden());
        upload(rec, other, "AGENT", visitStart, 60).andExpect(status().isForbidden());

        String job = claim();
        assertThat(job).contains(rec.toString()).contains("Suhbat Agent").contains("\"rubric\"").contains("salom");
        String result = """
            {"transcript":[{"t":"0:04","sec":4.2,"speaker":"spk_1","text":"Assalomu alaykum, BioLife dan keldim"},
                           {"t":"0:09","sec":9.0,"speaker":"spk_2","text":"Va alaykum"}],
             "language":"uz","agentSpeaker":"spk_1",
             "criteria":{"salom":50,"ehtiyoj":15,"taklif":18,"aksiya":12,"yangi":7,"etiroz":10,"yakun":9,"bogus":99},
             "promoMentioned":true,"flags":["BAD FLAG","short"],"summary":"Yaxshi suhbat","advice":"Ehtiyojni ko'proq so'rang",
             "evidence":[{"t":"0:04","who":"agent","text":"Assalomu alaykum","mark":"good","criterion":"salom"}],
             "models":{"stt":"gemini-3.5-transcribe","llm":"claude"}}""";
        as(post("/api/v1/internal/recordings/{id}/result", rec).contentType(MediaType.APPLICATION_JSON).content(result), UUID.randomUUID(), "ADMIN")
            .andExpect(status().isNoContent());
        // 10 (salom clamped from 50) + 15 + 18 + 12 + 7 + 10 + 9 = 81; unknown criterion and invalid flag dropped
        as(get("/api/v1/me/recordings").param("date", day.toString()), agent, "AGENT").andExpect(status().isOk())
            .andExpect(jsonPath("$[0].score").value(81)).andExpect(jsonPath("$[0].status").value("DONE"))
            .andExpect(jsonPath("$[0].flags.length()").value(1)).andExpect(jsonPath("$[0].criteria[0].points").value(10));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
            Integer.valueOf(81).equals(jdbc.queryForObject("select score from rating_visit_fact where visit_id = ?", Integer.class, visit))
            && jdbc.queryForObject("select coalesce(sum(score_count), 0) from report_agent_day where agent_id = ?", Integer.class, agent) == 1);
        // "short" is a note for the manager, not a suspect recording: the week's conversation bonus is not withheld
        assertThat(jdbc.queryForObject("select flagged from rating_visit_fact where visit_id = ?", Boolean.class, visit)).isFalse();

        as(get("/api/v1/panel/conversations").param("date", day.toString()), UUID.randomUUID(), "MANAGER").andExpect(status().isOk())
            .andExpect(jsonPath("$.counts.all").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.items[?(@.id == '" + rec + "')].agentName").value("Suhbat Agent"));
        as(get("/api/v1/panel/conversations/{id}", rec), UUID.randomUUID(), "MANAGER").andExpect(status().isOk())
            .andExpect(jsonPath("$.transcript.length()").value(2)).andExpect(jsonPath("$.evidence[0].criterion").value("salom"))
            .andExpect(jsonPath("$.visitId").value(visit.toString()));
        as(get("/api/v1/panel/conversations/{id}/audio", rec), UUID.randomUUID(), "MANAGER").andExpect(status().isOk()).andExpect(content().bytes(audio));
        as(get("/api/v1/panel/conversations/{id}/audio", rec), agent, "AGENT").andExpect(status().isOk());
        as(get("/api/v1/panel/conversations/{id}/audio", rec), other, "AGENT").andExpect(status().isForbidden());
        as(get("/api/v1/panel/conversations/{id}", rec), agent, "AGENT").andExpect(status().isForbidden());
    }

    @Test
    void workerFailureWaitsAndAnUnmatchedRecordingIsPublishedAfterThreeHours() throws Exception {
        UUID late = UUID.randomUUID();
        Instant fourHoursAgo = Instant.now().minus(Duration.ofHours(4));
        upload(late, other, "AGENT", fourHoursAgo, 120).andExpect(status().isOk());
        // drain whatever is due until we hold this one
        String job = "";
        for (int i = 0; i < 5 && !job.contains(late.toString()); i++) job = claim();
        assertThat(job).contains(late.toString());
        as(post("/api/v1/internal/recordings/{id}/fail", late).contentType(MediaType.APPLICATION_JSON)
            .content("{\"error\":\"Gemini quota\",\"retryable\":true,\"delaySec\":3600,\"countAttempt\":false}"), UUID.randomUUID(), "ADMIN")
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select attempts from conv_recording where id = ?", Integer.class, late)).isZero();
        assertThat(claim()).doesNotContain(late.toString());   // not due for an hour

        jdbc.update("update conv_recording set lease_until = null where id = ?", late);
        job = "";
        for (int i = 0; i < 5 && !job.contains(late.toString()); i++) job = claim();
        assertThat(job).contains(late.toString());
        as(post("/api/v1/internal/recordings/{id}/result", late).contentType(MediaType.APPLICATION_JSON)
            .content("{\"transcript\":[],\"criteria\":{\"salom\":5},\"flags\":[\"not_sales_talk\",\"single_voice\"],\"summary\":\"Savdo emas\"}"), UUID.randomUUID(), "ADMIN")
            .andExpect(status().isNoContent());
        // no Linko visit for this agent and it ended 4 h ago: published on its own id at once, as a suspect recording
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
            Boolean.TRUE.equals(jdbc.queryForObject("select published from conv_recording where id = ?", Boolean.class, late))
            && jdbc.queryForObject("select count(*) from rating_visit_fact where visit_id = ? and flagged and score = 5", Integer.class, late) == 1);
        as(post("/api/v1/panel/conversations/{id}/reanalyze", late), UUID.randomUUID(), "MANAGER").andExpect(status().isUnprocessableContent());

        // a recording with no speech is shown to the manager but never scored in the rating or reports
        UUID silent = UUID.randomUUID();
        upload(silent, other, "AGENT", fourHoursAgo.plusSeconds(600), 40).andExpect(status().isOk());
        job = "";
        for (int i = 0; i < 5 && !job.contains(silent.toString()); i++) job = claim();
        assertThat(job).contains(silent.toString());
        as(post("/api/v1/internal/recordings/{id}/result", silent).contentType(MediaType.APPLICATION_JSON)
            .content("{\"transcript\":[],\"criteria\":{\"salom\":0},\"flags\":[\"no_speech\"],\"summary\":\"Nutq yo'q\"}"), UUID.randomUUID(), "ADMIN")
            .andExpect(status().isNoContent());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
            Boolean.TRUE.equals(jdbc.queryForObject("select published from conv_recording where id = ?", Boolean.class, silent)));
        assertThat(jdbc.queryForObject("select count(*) from rating_visit_fact where visit_id = ?", Integer.class, silent)).isZero();
        as(get("/api/v1/panel/conversations").param("date", WorkHours.dateOf(fourHoursAgo).toString()).param("kind", "low"), UUID.randomUUID(), "MANAGER")
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id == '" + silent + "')]").isEmpty());
    }

    @Test
    void statsAndAnalystSettings() throws Exception {
        as(get("/api/v1/panel/conversations/stats").param("date", day.toString()), UUID.randomUUID(), "MANAGER").andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value(day.toString()));
        as(put("/api/v1/panel/conversations/settings").contentType(MediaType.APPLICATION_JSON)
            .content("{\"promoText\":\"  20 ta olsa 2 ta bonus  \",\"newProductText\":\"\"}"), UUID.randomUUID(), "MANAGER")
            .andExpect(status().isOk()).andExpect(jsonPath("$.promoText").value("20 ta olsa 2 ta bonus")).andExpect(jsonPath("$.newProductText").doesNotExist());
        as(get("/api/v1/panel/conversations/settings"), agent, "AGENT").andExpect(status().isForbidden());
        as(post("/api/v1/internal/recordings/claim"), agent, "AGENT").andExpect(status().isForbidden());
    }
}
