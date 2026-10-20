package uz.biolife.conversation.api;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import uz.biolife.conversation.application.ConversationDtos.AgentRecording;
import uz.biolife.conversation.application.ConversationQueries;
import uz.biolife.conversation.application.ConversationService;
import uz.biolife.shared.security.CurrentUser;
import uz.biolife.shared.security.ForbiddenException;

/** The agent's phone: send a recorded conversation, see today's recordings and their scores. */
@RestController
@RequestMapping("/api/v1/me/recordings")
public class MeRecordingsController {
    private final ConversationService service;
    private final ConversationQueries queries;

    public MeRecordingsController(ConversationService service, ConversationQueries queries) { this.service = service; this.queries = queries; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentRecording upload(CurrentUser user, @RequestParam("file") MultipartFile file, @RequestParam("id") UUID id,
                                 @RequestParam("startedAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedAt,
                                 @RequestParam("endedAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endedAt,
                                 @RequestParam("durationSec") int durationSec) throws IOException {
        if (user.role() != CurrentUser.Role.AGENT) throw new ForbiddenException("Suhbat yozuvini faqat agent yuboradi");
        try (InputStream in = file.getInputStream()) {
            return queries.forAgent(service.upload(user.id(), id, startedAt, endedAt, durationSec, file.getContentType(), file.getSize(), in));
        }
    }

    @GetMapping
    public List<AgentRecording> mine(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (user.role() != CurrentUser.Role.AGENT) throw new ForbiddenException("Bu agentning o'z yozuvlari");
        return queries.mine(user.id(), date);
    }
}
