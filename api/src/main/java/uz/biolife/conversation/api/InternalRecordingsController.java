package uz.biolife.conversation.api;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.conversation.application.ConversationService;
import uz.biolife.conversation.application.ConversationService.Job;
import uz.biolife.conversation.application.ConversationService.Result;
import uz.biolife.shared.security.CurrentUser;
import uz.biolife.shared.security.ForbiddenException;

/**
 * The AI worker's side (service account, ADMIN role; not reachable through the web): take the next recording,
 * report the transcript and scores, or report a failure with a retry time.
 */
@RestController
@RequestMapping("/api/v1/internal/recordings")
public class InternalRecordingsController {
    public record Failure(String error, Boolean retryable, Integer delaySec, Boolean countAttempt) {}

    private final ConversationService service;
    public InternalRecordingsController(ConversationService service) { this.service = service; }

    @PostMapping("/claim")
    public ResponseEntity<Job> claim(CurrentUser user) {
        requireService(user);
        return service.claim().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/result")
    public ResponseEntity<Void> result(CurrentUser user, @PathVariable UUID id, @RequestBody Result body) {
        requireService(user);
        service.result(id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<Void> fail(CurrentUser user, @PathVariable UUID id, @RequestBody Failure body) {
        requireService(user);
        service.fail(id, body.error() == null ? "noma'lum xato" : body.error(), !Boolean.FALSE.equals(body.retryable()),
            body.delaySec() == null ? 0 : body.delaySec(), !Boolean.FALSE.equals(body.countAttempt()));
        return ResponseEntity.noContent().build();
    }

    private static void requireService(CurrentUser user) {
        if (user.role() != CurrentUser.Role.ADMIN) throw new ForbiddenException("Faqat servis");
    }
}
