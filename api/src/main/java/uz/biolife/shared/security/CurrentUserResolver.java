package uz.biolife.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Resolves {@link CurrentUser}: first a Bearer JWT (issued by identity), then, only when
 * {@code biolife.auth.dev-headers=true} (local/test profiles), the X-User-Id / X-User-Role headers.
 * Missing or invalid identity → 401. Production must run with dev headers off.
 */
@Configuration
public class CurrentUserResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {
    public static final String HEADER_ID = "X-User-Id";
    public static final String HEADER_ROLE = "X-User-Role";

    private final JwtCodec jwt;
    private final boolean devHeaders;

    public CurrentUserResolver(JwtCodec jwt, @Value("${biolife.auth.dev-headers:false}") boolean devHeaders) {
        this.jwt = jwt; this.devHeaders = devHeaders;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) { resolvers.add(this); }

    @Override
    public boolean supportsParameter(MethodParameter parameter) { return CurrentUser.class.equals(parameter.getParameterType()); }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest web, WebDataBinderFactory binder) {
        HttpServletRequest req = web.getNativeRequest(HttpServletRequest.class);
        if (req == null) throw new UnauthorizedException("So'rov yo'q");
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return jwt.verify(auth.substring(7).trim())
                .map(c -> new CurrentUser(c.sub(), c.role()))
                .orElseThrow(() -> new UnauthorizedException("Token yaroqsiz yoki muddati o'tgan"));
        }
        if (devHeaders) {
            String id = req.getHeader(HEADER_ID), role = req.getHeader(HEADER_ROLE);
            if (id != null && role != null) {
                try { return new CurrentUser(UUID.fromString(id), CurrentUser.Role.valueOf(role.toUpperCase())); }
                catch (IllegalArgumentException e) { throw new UnauthorizedException("Dev sarlavha noto'g'ri"); }
            }
        }
        throw new UnauthorizedException("Kirish talab qilinadi");
    }
}
