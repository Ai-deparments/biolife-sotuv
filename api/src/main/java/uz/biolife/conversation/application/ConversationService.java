package uz.biolife.conversation.application;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.biolife.conversation.domain.ConvAgent;
import uz.biolife.conversation.domain.ConvSettings;
import uz.biolife.conversation.domain.ConvVisit;
import uz.biolife.conversation.domain.Recording;
import uz.biolife.conversation.infra.ConvAgentRepository;
import uz.biolife.conversation.infra.ConvSettingsRepository;
import uz.biolife.conversation.infra.ConvVisitRepository;
import uz.biolife.conversation.infra.RecordingRepository;
import uz.biolife.shared.events.DomainEvents.AnalysisCompleted;
import uz.biolife.shared.persistence.AggregateLock;
import uz.biolife.shared.security.ForbiddenException;
import uz.biolife.shared.time.WorkHours;
import uz.biolife.shared.web.NotFoundException;

/**
 * Recording lifecycle: upload → queue → worker claims (lease) → result or retry → linked to the Linko visit by time →
 * {@code AnalysisCompleted} published once (rating and reporting take the score from there).
 * A recording waits up to {@link #MATCH_WAIT} for its Linko visit; after that it is published on its own id.
 */
@Service
public class ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    static final Duration LEASE = Duration.ofMinutes(15);
    static final Duration MATCH_WAIT = Duration.ofHours(3);
    static final Duration SLACK = Duration.ofMinutes(5);
    static final Duration NEAREST = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 6;
    public static final long MAX_BYTES = 60L * 1024 * 1024;
    public static final int MAX_SECONDS = 30 * 60;
    private static final Pattern FLAG = Pattern.compile("[a-z_]{2,30}");
    /** Nothing to judge (no speech, unintelligible audio): kept in the panel, not sent to rating or reports. */
    public static final Set<String> NOT_ASSESSABLE = Set.of("no_speech", "unclear_audio");
    /** Suspect recording: the rating withholds the week's conversation bonus. Every other flag is a note for the manager. */
    public static final Set<String> SUSPECT = Set.of("not_sales_talk");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** What the worker needs for one recording. */
    public record Job(UUID id, UUID agentId, String agentName, Instant startedAt, Instant endedAt, int durationSec, String mime,
                      String filePath, String promoText, String newProductText, int attempt, List<Rubric.Item> rubric) {}

    /** The worker's answer: transcript (speaker turns) and the rubric scores with evidence. */
    public record Result(List<Map<String, Object>> transcript, String language, String agentSpeaker, Map<String, Integer> criteria,
                         Boolean promoMentioned, List<String> flags, String summary, String advice,
                         List<Map<String, Object>> evidence, Map<String, String> models) {}

    private final RecordingRepository recordings;
    private final ConvVisitRepository visits;
    private final ConvAgentRepository agents;
    private final ConvSettingsRepository settings;
    private final RecordingStorage storage;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final AggregateLock lock;
    private final Clock clock;
    private final int keepDays;

    public ConversationService(RecordingRepository recordings, ConvVisitRepository visits, ConvAgentRepository agents, ConvSettingsRepository settings,
                               RecordingStorage storage, ApplicationEventPublisher events, ObjectMapper mapper, AggregateLock lock, Clock clock,
                               @org.springframework.beans.factory.annotation.Value("${biolife.recordings.keep-days:180}") int keepDays) {
        this.recordings = recordings; this.visits = visits; this.agents = agents; this.settings = settings; this.storage = storage;
        this.events = events; this.mapper = mapper; this.lock = lock; this.clock = clock; this.keepDays = Math.max(7, keepDays);
    }

    /** Idempotent by the client-made id: a retried upload returns the recording that already arrived. */
    @Transactional
    public Recording upload(UUID agentId, UUID id, Instant startedAt, Instant endedAt, int durationSec, String contentType, long size, InputStream in) {
        lock.acquire("conv:recording:" + id);
        Optional<Recording> existing = recordings.findById(id);
        if (existing.isPresent()) {
            if (!existing.get().getAgentId().equals(agentId)) throw new ForbiddenException("Bu yozuv boshqa agentniki");
            return existing.get();
        }
        if (size <= 0) throw new IllegalArgumentException("Yozuv bo'sh. Qayta yozib ko'ring.");
        if (size > MAX_BYTES) throw new IllegalArgumentException("Yozuv juda katta (60 MB dan oshmasin)");
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) throw new IllegalArgumentException("Yozuv vaqti noto'g'ri");
        if (durationSec < 1 || durationSec > MAX_SECONDS + 60) throw new IllegalArgumentException("Yozuv 30 daqiqadan oshmasin");
        String mime = RecordingStorage.baseMime(contentType);
        if (!mime.startsWith("audio/") && !mime.equals("video/webm") && !mime.equals("application/octet-stream")) throw new IllegalArgumentException("Bu ovoz yozuvi emas");
        LocalDate date = WorkHours.dateOf(startedAt);
        String path = storage.save(id, date, mime, in).toString();
        Recording r = new Recording(id, agentId, startedAt, endedAt, durationSec, date, mime, size, path, clock.instant());
        link(r);
        log.info("recording {} from agent {}: {} s, {} bytes", id, agentId, durationSec, size);
        return recordings.save(r);
    }

    @Transactional
    public Optional<Job> claim() {
        Instant now = clock.instant();
        Optional<Recording> next = recordings.nextDue(now);
        if (next.isEmpty()) return Optional.empty();
        Recording r = next.get();
        if (r.getAttempts() >= MAX_ATTEMPTS) {
            r.fail("Bir necha urinishdan keyin ham tahlil qilinmadi. Oxirgi xato: " + r.getError(), now);
            return Optional.empty();
        }
        r.claim(now, now.plus(LEASE));
        ConvSettings s = settings();
        String agentName = agents.findById(r.getAgentId()).map(ConvAgent::getName).orElse(null);
        return Optional.of(new Job(r.getId(), r.getAgentId(), agentName, r.getStartedAt(), r.getEndedAt(), r.getDurationSec(), r.getMime(),
            r.getFilePath(), s.getPromoText(), s.getNewProductText(), r.getAttempts(), Rubric.ITEMS));
    }

    @Transactional
    public Recording result(UUID id, Result b) {
        lock.acquire("conv:recording:" + id);
        Recording r = recordings.findById(id).orElseThrow(() -> new NotFoundException("Yozuv topilmadi"));
        if (r.getStatus() == Recording.Status.DONE) return r;
        Map<String, Integer> crit = new LinkedHashMap<>();
        int score = 0;
        for (Rubric.Item item : Rubric.ITEMS) {
            Integer v = b.criteria() == null ? null : b.criteria().get(item.code());
            int points = Math.max(0, Math.min(item.weight(), v == null ? 0 : v));
            crit.put(item.code(), points);
            score += points;
        }
        List<String> flags = new ArrayList<>();
        if (b.flags() != null) for (String f : b.flags()) if (f != null && FLAG.matcher(f).matches() && !flags.contains(f) && flags.size() < 6) flags.add(f);
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("criteria", crit);
        analysis.put("evidence", b.evidence() == null ? List.of() : b.evidence().stream().limit(20).toList());
        analysis.put("agentSpeaker", b.agentSpeaker());
        analysis.put("language", b.language());
        analysis.put("models", b.models());
        Instant now = clock.instant();
        r.done(mapper.writeValueAsString(b.transcript() == null ? List.of() : b.transcript()), mapper.writeValueAsString(analysis), score,
            Boolean.TRUE.equals(b.promoMentioned()), String.join(",", flags), cut(b.summary(), 1000), cut(b.advice(), 1000), now);
        tryPublish(r);
        log.info("recording {} analysed: score {} flags {}", id, score, flags);
        return r;
    }

    /** Worker could not finish. Quota waits do not count as an attempt. */
    @Transactional
    public void fail(UUID id, String error, boolean retryable, int delaySec, boolean countAttempt) {
        lock.acquire("conv:recording:" + id);
        Recording r = recordings.findById(id).orElseThrow(() -> new NotFoundException("Yozuv topilmadi"));
        if (r.getStatus() == Recording.Status.DONE) return;
        Instant now = clock.instant();
        if (!retryable || (countAttempt && r.getAttempts() >= MAX_ATTEMPTS)) { r.fail(error, now); return; }
        long backoff = delaySec > 0 ? delaySec : new long[] {120, 300, 900, 1800, 3600, 3600}[Math.min(5, Math.max(0, r.getAttempts() - 1))];
        r.retryLater(error, now.plusSeconds(Math.min(6 * 3600, backoff)), now);
        if (!countAttempt) r.uncountAttempt();
    }

    /** Panel: run a failed recording again. */
    @Transactional
    public void requeue(UUID id) {
        Recording r = recordings.findById(id).orElseThrow(() -> new NotFoundException("Yozuv topilmadi"));
        if (r.getStatus() != Recording.Status.FAILED) throw new IllegalArgumentException("Faqat tahlil qilinmay qolgan yozuvni qayta yuborish mumkin");
        r.requeue(clock.instant());
    }

    @Transactional
    public ConvSettings settings() { return settings.findById(ConvSettings.SINGLETON).orElseGet(() -> settings.save(ConvSettings.empty())); }

    @Transactional
    public ConvSettings updateSettings(String promo, String newProduct) {
        ConvSettings s = settings();
        s.update(promo, newProduct, clock.instant());
        return settings.save(s);
    }

    /** Nightly: audio older than keep-days is deleted from disk; transcript, scores and evidence stay in the database. */
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Tashkent")
    @Transactional(readOnly = true)
    public void deleteOldAudio() {
        LocalDate before = LocalDate.now(clock).minusDays(keepDays);
        int deleted = 0;
        for (Recording r : recordings.findByDateBefore(before)) {
            try { if (java.nio.file.Files.deleteIfExists(storage.resolve(r.getFilePath()))) deleted++; }
            catch (java.io.IOException | RuntimeException e) { log.warn("audio not deleted {}: {}", r.getId(), e.getMessage()); }
        }
        if (deleted > 0) log.info("old audio deleted: {} files older than {}", deleted, before);
    }

    /** Every two minutes: link new recordings to their Linko visits and publish analyses whose visit is known (or waited long enough). */
    @Scheduled(initialDelay = 60_000, fixedDelay = 120_000)
    @Transactional
    public void matchAndPublish() {
        Instant now = clock.instant();
        for (Recording r : recordings.unlinkedSince(now.minus(Duration.ofDays(2)))) link(r);
        for (Recording r : recordings.doneNotPublished()) tryPublish(r);
    }

    void tryPublish(Recording r) {
        if (r.getStatus() != Recording.Status.DONE || r.isPublished()) return;
        if (r.getVisitId() == null) link(r);
        boolean waitedEnough = clock.instant().isAfter(r.getEndedAt().plus(MATCH_WAIT));
        if (r.getVisitId() == null && !waitedEnough) return;
        List<String> flags = flags(r);
        if (flags.stream().anyMatch(NOT_ASSESSABLE::contains)) {
            r.markPublished(clock.instant());   // handled: nothing to score, the panel still shows it with the flag
            log.info("recording {} not scored ({}): not sent to rating", r.getId(), flags);
            return;
        }
        UUID visitId = r.getVisitId() != null ? r.getVisitId() : r.getId();
        events.publishEvent(new AnalysisCompleted(r.getAnalysisId(), visitId, r.getAgentId(), r.getDate(), r.getScore() == null ? 0 : r.getScore(),
            criteria(r), Boolean.TRUE.equals(r.getPromoMentioned()), flags.stream().filter(SUSPECT::contains).toList(), r.getSummary()));
        r.markPublished(clock.instant());
    }

    /** Linko visit of the same agent that day with the largest time overlap; else the nearest start within 15 minutes. */
    void link(Recording r) {
        ConvVisit best = null;
        long bestOverlap = 0;
        List<ConvVisit> cands = visits.findByAgentIdAndDate(r.getAgentId(), r.getDate());
        for (ConvVisit v : cands) {
            Instant vs = v.getStartedAt().minus(SLACK);
            Instant ve = (v.getEndedAt() == null ? v.getStartedAt() : v.getEndedAt()).plus(SLACK);
            Instant from = vs.isAfter(r.getStartedAt()) ? vs : r.getStartedAt();
            Instant to = ve.isBefore(r.getEndedAt()) ? ve : r.getEndedAt();
            long overlap = Duration.between(from, to).toSeconds();
            if (overlap > bestOverlap) { bestOverlap = overlap; best = v; }
        }
        if (best == null) {
            long nearest = NEAREST.toSeconds() + 1;
            for (ConvVisit v : cands) {
                long d = Math.abs(Duration.between(v.getStartedAt(), r.getStartedAt()).toSeconds());
                if (d < nearest) { nearest = d; best = v; }
            }
        }
        if (best != null) r.linkVisit(best.getVisitId(), best.getOutletName(), clock.instant());
    }

    @SuppressWarnings("unchecked")
    Map<String, Integer> criteria(Recording r) {
        if (r.getAnalysis() == null) return Map.of();
        Object c = mapper.readValue(r.getAnalysis(), MAP).get("criteria");
        Map<String, Integer> out = new LinkedHashMap<>();
        if (c instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), v instanceof Number n ? n.intValue() : 0));
        return out;
    }

    static List<String> flags(Recording r) {
        return r.getFlags() == null || r.getFlags().isBlank() ? List.of() : List.of(r.getFlags().split(","));
    }

    private static String cut(String s, int max) { return s == null ? null : s.length() <= max ? s.strip() : s.substring(0, max).strip(); }
}
