package uz.biolife.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal HS256 JWT: {sub, role, name, exp}. Issued by identity, verified by {@link CurrentUserResolver}.
 * Kept dependency-free on purpose; replace with Spring Security's JWT support when OAuth/SSO is needed.
 */
@Component
public class JwtCodec {
    public record Claims(UUID sub, CurrentUser.Role role, String name, Instant exp) {}
    private record Payload(String sub, String role, String name, long exp) {}

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JwtCodec(@Value("${biolife.auth.jwt-secret}") String secret, @Value("${biolife.auth.jwt-ttl:PT12H}") Duration ttl, ObjectMapper mapper, Clock clock) {
        if (secret == null || secret.length() < 32) throw new IllegalStateException("biolife.auth.jwt-secret kamida 32 belgi bo'lishi kerak");
        this.secret = secret.getBytes(StandardCharsets.UTF_8); this.ttl = ttl; this.mapper = mapper; this.clock = clock;
    }

    public String issue(UUID sub, CurrentUser.Role role, String name) {
        Instant exp = clock.instant().plus(ttl);
        String payload = ENC.encodeToString(mapper.writeValueAsBytes(new Payload(sub.toString(), role.name(), name, exp.getEpochSecond())));
        String signing = HEADER + "." + payload;
        return signing + "." + sign(signing);
    }

    public Optional<Claims> verify(String token) {
        if (token == null) return Optional.empty();
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !parts[0].equals(HEADER)) return Optional.empty();
        byte[] expected = sign(parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.UTF_8))) return Optional.empty();
        try {
            Payload p = mapper.readValue(DEC.decode(parts[1]), Payload.class);
            Instant exp = Instant.ofEpochSecond(p.exp());
            if (exp.isBefore(clock.instant())) return Optional.empty();
            return Optional.of(new Claims(UUID.fromString(p.sub()), CurrentUser.Role.valueOf(p.role()), p.name(), exp));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public Duration ttl() { return ttl; }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return ENC.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
