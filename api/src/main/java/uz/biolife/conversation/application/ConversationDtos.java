package uz.biolife.conversation.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConversationDtos {
    private ConversationDtos() {}

    public record CriterionScore(String code, String title, int points, int weight) {}

    /** What the agent sees about their own recording. */
    public record AgentRecording(UUID id, Instant startedAt, int durationSec, String status, Integer score, String outletName,
                                 String summary, String advice, List<CriterionScore> criteria, List<String> flags) {}

    public record Row(UUID id, UUID agentId, String agentName, String region, LocalDate date, Instant startedAt, int durationSec, String status,
                      Integer score, String outletName, List<String> flags, String summary) {}

    public record Page(LocalDate date, String kind, int page, int size, long total, Map<String, Long> counts, List<String> regions, List<Row> items) {}

    public record Detail(Row row, String error, int attempts, List<Map<String, Object>> transcript, List<CriterionScore> criteria,
                         List<Map<String, Object>> evidence, String advice, String agentSpeaker, String language, Boolean promoMentioned,
                         UUID visitId, Map<String, Object> models) {}

    public record Stats(LocalDate date, long recorded, long analysed, long pending, long failed, Integer avgScore, long agents) {}

    public record Settings(String promoText, String newProductText, Instant updatedAt) {}
}
