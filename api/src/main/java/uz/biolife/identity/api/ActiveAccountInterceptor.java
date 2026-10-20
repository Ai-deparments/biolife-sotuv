package uz.biolife.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uz.biolife.identity.application.AuthService;
import uz.biolife.shared.security.JwtCodec;

/** A disabled account loses access within a minute, not only when its 12-hour token expires. */
@Configuration
public class ActiveAccountInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final JwtCodec jwt;
    private final AuthService auth;

    public ActiveAccountInterceptor(JwtCodec jwt, AuthService auth) { this.jwt = jwt; this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/v1/auth/login"); }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return true;
        var claims = jwt.verify(h.substring(7).trim());
        if (claims.isEmpty() || auth.isActive(claims.get().sub())) return true;
        res.setStatus(401);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"type\":\"https://biolife.uz/errors/unauthorized\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Hisobingiz o'chirilgan. Rahbarga murojaat qiling.\"}");
        return false;
    }

    @Scheduled(fixedDelay = 60_000)
    public void refresh() { auth.forgetActiveCache(); }
}
