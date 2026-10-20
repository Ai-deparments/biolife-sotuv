package uz.biolife.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import uz.biolife.shared.security.CurrentUser.Role;

/** Login account. For an agent, {@code id} equals the agent id used by every other module. */
@Entity(name = "IdentityUser")
@Table(name = "identity_user")
public class User {
    @Id private UUID id;
    private String username;
    private String passwordHash;
    private String name;
    private String role;
    private boolean active;
    private Instant createdAt;
    private Instant lastLoginAt;

    protected User() {}
    public User(UUID id, String username, String passwordHash, String name, Role role, Instant now) {
        this.id = id; this.username = username.toLowerCase(); this.passwordHash = passwordHash; this.name = name;
        this.role = role.name(); this.active = true; this.createdAt = now;
    }
    public void loggedIn(Instant now) { this.lastLoginAt = now; }
    public void changePassword(String hash) { this.passwordHash = hash; }
    public void rename(String name) { this.name = name; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public Role getRole() { return Role.valueOf(role); }
    public boolean isActive() { return active; }
    public Instant getLastLoginAt() { return lastLoginAt; }
}
