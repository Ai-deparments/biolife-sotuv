package uz.biolife.visit.api;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.shared.security.CurrentUser;
import uz.biolife.shared.security.ForbiddenException;
import uz.biolife.visit.application.PhotoCheckService;
import uz.biolife.visit.application.PhotoCheckService.Batch;
import uz.biolife.visit.application.PhotoCheckService.Result;

/**
 * The photo AI worker's side (service account, ADMIN role; not reachable through the web): take a batch of visits with
 * shelf photos, report what was seen on them, or report a failure with a retry time.
 */
@RestController
@RequestMapping("/api/v1/internal/photos")
public class InternalPhotosController {
    public record Failure(String error, Boolean retryable, Integer delaySec, Boolean countAttempt) {}

    private final PhotoCheckService service;
    public InternalPhotosController(PhotoCheckService service) { this.service = service; }

    @PostMapping("/claim")
    public ResponseEntity<Batch> claim(CurrentUser user, @RequestParam(defaultValue = "5") int limit) {
        requireService(user);
        Batch b = service.claim(limit);
        return b.items().isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(b);
    }

    @PostMapping("/{visitId}/result")
    public ResponseEntity<Void> result(CurrentUser user, @PathVariable UUID visitId, @RequestBody Result body) {
        requireService(user);
        service.result(visitId, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{visitId}/fail")
    public ResponseEntity<Void> fail(CurrentUser user, @PathVariable UUID visitId, @RequestBody Failure body) {
        requireService(user);
        service.fail(visitId, body.error() == null ? "noma'lum xato" : body.error(), !Boolean.FALSE.equals(body.retryable()),
            body.delaySec() == null ? 0 : body.delaySec(), !Boolean.FALSE.equals(body.countAttempt()));
        return ResponseEntity.noContent().build();
    }

    private static void requireService(CurrentUser user) {
        if (user.role() != CurrentUser.Role.ADMIN) throw new ForbiddenException("Faqat servis");
    }
}
