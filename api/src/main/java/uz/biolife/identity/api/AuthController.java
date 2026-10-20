package uz.biolife.identity.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.identity.application.AuthService;
import uz.biolife.identity.application.AuthService.Session;
import uz.biolife.shared.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record PasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record Me(java.util.UUID id, CurrentUser.Role role) {}

    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public Session login(@RequestBody @Valid LoginRequest req) { return auth.login(req.username(), req.password()); }

    /** Who am I (token check for the web shell). */
    @GetMapping("/me")
    public Me me(CurrentUser user) { return new Me(user.id(), user.role()); }

    /** Change own password. */
    @PostMapping("/password")
    public Map<String, Boolean> password(CurrentUser user, @RequestBody @Valid PasswordRequest req) {
        auth.changePassword(user.id(), req.currentPassword(), req.newPassword());
        return Map.of("ok", true);
    }
}
