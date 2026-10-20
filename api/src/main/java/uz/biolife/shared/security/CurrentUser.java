package uz.biolife.shared.security;

import java.util.UUID;

/** The authenticated caller. Resolved per request; see {@link CurrentUserResolver}. */
public record CurrentUser(UUID id, Role role) {
    public enum Role { AGENT, MANAGER, ADMIN }

    public boolean isManager() { return role == Role.MANAGER || role == Role.ADMIN; }

    public void requireManager() {
        if (!isManager()) throw new ForbiddenException("Faqat rahbar uchun");
    }

    public void requireSelfOrManager(UUID agentId) {
        if (!isManager() && !id.equals(agentId)) throw new ForbiddenException("Boshqa agentning ma'lumoti");
    }
}
