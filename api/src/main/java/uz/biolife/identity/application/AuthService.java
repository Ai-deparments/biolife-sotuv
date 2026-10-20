package uz.biolife.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.biolife.identity.domain.User;
import uz.biolife.identity.infra.UserRepository;
import uz.biolife.shared.security.CurrentUser.Role;
import uz.biolife.shared.security.JwtCodec;
import uz.biolife.shared.security.TooManyRequestsException;
import uz.biolife.shared.security.UnauthorizedException;
import uz.biolife.shared.web.NotFoundException;

/**
 * Username/password login → JWT. Same error for unknown user and wrong password (no user enumeration).
 * After {@value #MAX_FAILS} wrong passwords for one login within 15 minutes the login is locked for 15 minutes.
 * Managers manage other manager accounts; agents and service accounts are not touched from the panel.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    static final int MAX_FAILS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,32}");

    public record Session(String accessToken, long expiresInSec, UUID userId, String name, Role role) {}
    public record UserView(UUID id, String username, String name, Role role, boolean active, Instant createdAt, Instant lastLoginAt) {}

    private static final class Attempts { int fails; Instant first; Instant lockedUntil; }

    private final UserRepository users;
    private final JwtCodec jwt;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> activeCache = new ConcurrentHashMap<>();

    public AuthService(UserRepository users, JwtCodec jwt, Clock clock) { this.users = users; this.jwt = jwt; this.clock = clock; }

    @Transactional
    public Session login(String username, String password) {
        String key = username == null ? "" : username.trim().toLowerCase();
        Instant now = clock.instant();
        Attempts a = attempts.get(key);
        if (a != null && a.lockedUntil != null && now.isBefore(a.lockedUntil)) {
            long min = Duration.between(now, a.lockedUntil).toMinutes() + 1;
            throw new TooManyRequestsException("Parol ko'p marta noto'g'ri kiritildi. " + min + " daqiqadan keyin qayta urinib ko'ring.");
        }
        User u = users.findByUsername(key).orElse(null);
        if (u == null || !u.isActive() || !encoder.matches(password == null ? "" : password, u.getPasswordHash())) {
            registerFail(key, now);
            log.info("login failed username={}", key);
            throw new UnauthorizedException("Login yoki parol noto'g'ri. Katta-kichik harfni tekshirib, qayta kiriting.");
        }
        attempts.remove(key);
        u.loggedIn(now);
        return new Session(jwt.issue(u.getId(), u.getRole(), u.getName()), jwt.ttl().toSeconds(), u.getId(), u.getName(), u.getRole());
    }

    private void registerFail(String key, Instant now) {
        if (attempts.size() > 10_000) attempts.entrySet().removeIf(e -> e.getValue().first.plus(WINDOW).isBefore(now) && (e.getValue().lockedUntil == null || e.getValue().lockedUntil.isBefore(now)));
        attempts.compute(key, (k, a) -> {
            if (a == null || a.first.plus(WINDOW).isBefore(now)) { a = new Attempts(); a.first = now; }
            a.fails++;
            if (a.fails >= MAX_FAILS) { a.lockedUntil = now.plus(WINDOW); a.fails = 0; a.first = now; log.warn("login locked for 15 min: {}", k); }
            return a;
        });
    }

    @Transactional
    public User createUser(UUID id, String username, String password, String name, Role role) {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("Parol kamida 8 belgi");
        if (users.findByUsername(username.toLowerCase()).isPresent()) throw new IllegalArgumentException("Bu login band");
        return users.save(new User(id, username, encoder.encode(password), name, role, clock.instant()));
    }

    public boolean hasUsers() { return users.count() > 0; }

    /** Own password change: the current password is required. */
    @Transactional
    public void changePassword(UUID userId, String current, String next) {
        User u = users.findById(userId).orElseThrow(() -> new UnauthorizedException("Hisob topilmadi, qayta kiring"));
        if (!encoder.matches(current == null ? "" : current, u.getPasswordHash())) throw new IllegalArgumentException("Hozirgi parol noto'g'ri");
        checkPassword(next);
        u.changePassword(encoder.encode(next));
    }

    @Transactional(readOnly = true)
    public List<UserView> managers() { return accounts(Role.MANAGER); }

    /** Accounts of one role (MANAGER or AGENT); service accounts are never listed. */
    @Transactional(readOnly = true)
    public List<UserView> accounts(Role role) {
        if (role == Role.ADMIN) throw new IllegalArgumentException("Servis hisoblari ko'rsatilmaydi");
        return users.findByRoleOrderByNameAsc(role.name()).stream().map(AuthService::view).toList();
    }

    /** Login for a field agent: the account id IS the agent id, so the agent sees their own rating and recordings. */
    @Transactional
    public UserView createAgentLogin(UUID agentId, String name, String username, String password) {
        if (agentId == null) throw new IllegalArgumentException("Agentni tanlang");
        if (users.existsById(agentId)) throw new IllegalArgumentException("Bu agentga login allaqachon berilgan — ro'yxatdan topib, parolini yangilang");
        String login = username == null ? "" : username.trim().toLowerCase();
        if (!USERNAME.matcher(login).matches()) throw new IllegalArgumentException("Login 3–32 belgi: lotin harflari, raqam, nuqta, chiziqcha");
        checkName(name);
        checkPassword(password);
        return view(createUser(agentId, login, password, name.trim(), Role.AGENT));
    }

    @Transactional
    public UserView createManager(String username, String name, String password) {
        String login = username == null ? "" : username.trim().toLowerCase();
        if (!USERNAME.matcher(login).matches()) throw new IllegalArgumentException("Login 3–32 belgi: lotin harflari, raqam, nuqta, chiziqcha");
        checkName(name);
        checkPassword(password);
        return view(createUser(UUID.randomUUID(), login, password, name.trim(), Role.MANAGER));
    }

    /** Rename, enable/disable or set a new password for a manager or agent account. Nobody can disable their own account. */
    @Transactional
    public UserView updateManager(UUID actorId, UUID id, String name, Boolean active, String newPassword) {
        User u = users.findById(id).filter(x -> x.getRole() != Role.ADMIN).orElseThrow(() -> new NotFoundException("Hisob topilmadi"));
        if (name != null) { checkName(name); u.rename(name.trim()); }
        if (active != null) {
            if (!active && id.equals(actorId)) throw new IllegalArgumentException("O'z hisobingizni o'chira olmaysiz");
            u.setActive(active);
            activeCache.remove(id);
        }
        if (newPassword != null && !newPassword.isBlank()) { checkPassword(newPassword); u.changePassword(encoder.encode(newPassword)); }
        return view(u);
    }

    /** Is the account behind a token still active? Cached for a minute per user. */
    @Transactional(readOnly = true)
    public boolean isActive(UUID userId) {
        Boolean cached = activeCache.get(userId);
        if (cached != null) return cached;
        boolean active = users.findById(userId).map(User::isActive).orElse(false);
        activeCache.put(userId, active);
        return active;
    }

    /** Called every minute so a re-enabled or disabled account takes effect quickly. */
    public void forgetActiveCache() { activeCache.clear(); }

    private static void checkPassword(String p) {
        if (p == null || p.length() < 8) throw new IllegalArgumentException("Yangi parol kamida 8 belgi bo'lsin");
        if (p.length() > 100) throw new IllegalArgumentException("Parol 100 belgidan oshmasin");
    }
    private static void checkName(String n) {
        if (n == null || n.trim().length() < 2 || n.trim().length() > 120) throw new IllegalArgumentException("Ism 2–120 belgi bo'lsin");
    }
    private static UserView view(User u) {
        return new UserView(u.getId(), u.getUsername(), u.getName(), u.getRole(), u.isActive(), u.getCreatedAt(), u.getLastLoginAt());
    }
}
