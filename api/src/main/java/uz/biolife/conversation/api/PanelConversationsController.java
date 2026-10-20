package uz.biolife.conversation.api;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.conversation.application.ConversationDtos.Detail;
import uz.biolife.conversation.application.ConversationDtos.Page;
import uz.biolife.conversation.application.ConversationDtos.Settings;
import uz.biolife.conversation.application.ConversationDtos.Stats;
import uz.biolife.conversation.application.ConversationQueries;
import uz.biolife.conversation.application.ConversationService;
import uz.biolife.conversation.application.RecordingStorage;
import uz.biolife.conversation.domain.ConvSettings;
import uz.biolife.conversation.domain.Recording;
import uz.biolife.conversation.infra.RecordingRepository;
import uz.biolife.shared.security.CurrentUser;
import uz.biolife.shared.web.NotFoundException;

/** Manager: conversations of a day, one conversation with transcript and evidence, its audio, the analyst's settings. */
@RestController
@RequestMapping("/api/v1/panel/conversations")
public class PanelConversationsController {
    private final ConversationQueries queries;
    private final ConversationService service;
    private final RecordingRepository recordings;
    private final RecordingStorage storage;

    public PanelConversationsController(ConversationQueries queries, ConversationService service, RecordingRepository recordings, RecordingStorage storage) {
        this.queries = queries; this.service = service; this.recordings = recordings; this.storage = storage;
    }

    /** kind = all | pending | low | best | flagged | failed; newest first. */
    @GetMapping
    public Page list(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                     @RequestParam(defaultValue = "all") String kind, @RequestParam(required = false) String region,
                     @RequestParam(required = false) String q, @RequestParam(required = false) UUID agentId,
                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        user.requireManager();
        return queries.list(date, kind, region, q, agentId, page, size);
    }

    @GetMapping("/stats")
    public Stats stats(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        user.requireManager();
        return queries.stats(date);
    }

    @GetMapping("/{id}")
    public Detail detail(CurrentUser user, @PathVariable UUID id) {
        user.requireManager();
        return queries.detail(id);
    }

    /** The recording itself; the agent may listen to their own. */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(CurrentUser user, @PathVariable UUID id) {
        Recording r = recordings.findById(id).orElseThrow(() -> new NotFoundException("Yozuv topilmadi"));
        user.requireSelfOrManager(r.getAgentId());
        var path = storage.resolve(r.getFilePath());
        if (!Files.isReadable(path)) throw new NotFoundException("Yozuv fayli topilmadi");
        MediaType type = r.getMime() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(r.getMime());
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).body(new FileSystemResource(path));
    }

    @PostMapping("/{id}/reanalyze")
    public Detail reanalyze(CurrentUser user, @PathVariable UUID id) {
        user.requireManager();
        service.requeue(id);
        return queries.detail(id);
    }

    @GetMapping("/settings")
    public Settings settings(CurrentUser user) {
        user.requireManager();
        ConvSettings s = service.settings();
        return new Settings(s.getPromoText(), s.getNewProductText(), s.getUpdatedAt());
    }

    @PutMapping("/settings")
    public Settings updateSettings(CurrentUser user, @RequestBody Settings body) {
        user.requireManager();
        ConvSettings s = service.updateSettings(body.promoText(), body.newProductText());
        return new Settings(s.getPromoText(), s.getNewProductText(), s.getUpdatedAt());
    }
}
