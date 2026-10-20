package uz.biolife.identity.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.identity.application.AuthService;
import uz.biolife.identity.application.AuthService.UserView;
import uz.biolife.shared.security.CurrentUser;

/** Manager accounts of the panel (create, rename, enable/disable, new password). */
@RestController
@RequestMapping("/api/v1/panel/users")
public class UsersController {
    public record CreateRequest(@NotBlank String username, @NotBlank String name, @NotBlank String password) {}
    public record AgentLoginRequest(@jakarta.validation.constraints.NotNull UUID agentId, @NotBlank String name, @NotBlank String username, @NotBlank String password) {}
    public record UpdateRequest(String name, Boolean active, String password) {}

    private final AuthService auth;
    public UsersController(AuthService auth) { this.auth = auth; }

    /** role = MANAGER (default) or AGENT. */
    @GetMapping
    public List<UserView> list(CurrentUser user, @org.springframework.web.bind.annotation.RequestParam(defaultValue = "MANAGER") String role) {
        user.requireManager();
        return auth.accounts(parseRole(role));
    }

    @PostMapping("/agents")
    public UserView createAgentLogin(CurrentUser user, @RequestBody @Valid AgentLoginRequest req) {
        user.requireManager();
        return auth.createAgentLogin(req.agentId(), req.name(), req.username(), req.password());
    }

    private static uz.biolife.shared.security.CurrentUser.Role parseRole(String role) {
        try { return uz.biolife.shared.security.CurrentUser.Role.valueOf(role.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Rol: MANAGER yoki AGENT"); }
    }

    @PostMapping
    public UserView create(CurrentUser user, @RequestBody @Valid CreateRequest req) {
        user.requireManager();
        return auth.createManager(req.username(), req.name(), req.password());
    }

    @PatchMapping("/{id}")
    public UserView update(CurrentUser user, @PathVariable UUID id, @RequestBody UpdateRequest req) {
        user.requireManager();
        return auth.updateManager(user.id(), id, req.name(), req.active(), req.password());
    }
}
