package se.ostberg.pluggis.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Inloggning till föräldravyn (/auth, utanför /api så att den inte kräver token).
 * Första gången väljs ett lösenord – då behövs PLUGGIS_DEVICE_TOKEN en gång, så att inte vem som helst
 * på nätet hinner före.
 */
@RestController
@RequestMapping("/auth")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final AdminAuth auth;
    private final PluggisProperties props;
    private final LoginGuard guard = new LoginGuard(10, Duration.ofMinutes(10));

    public AdminAuthController(AdminAuth auth, PluggisProperties props) {
        this.auth = auth;
        this.props = props;
    }

    public record SetupRequest(String password, String token) {
    }

    public record LoginRequest(String password) {
    }

    /** Är lösenord valt, och är den här webbläsaren inloggad (cookie eller token)? */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return Map.of("passwordSet", auth.passwordSet(), "loggedIn", loggedIn(req), "tokenRequired", props.tokenRequired());
    }

    /** Välj lösenord första gången (med token), eller byt lösenord när man är inloggad. */
    @PostMapping("/password")
    public ResponseEntity<?> setPassword(@RequestBody SetupRequest body, HttpServletRequest req, HttpServletResponse res) {
        // Byta lösenord: inloggad. Välja första gången: token i rutan, eller redan inne med token (header).
        boolean allowed = loggedIn(req) || (!auth.passwordSet() && props.tokenMatches(body.token()));
        if (!allowed) {
            log.warn("Lösenord för föräldravyn nekat från {}: {}", req.getRemoteAddr(),
                    auth.passwordSet() ? "inte inloggad" : "fel token");
            return ResponseEntity.status(401).body(Map.of("error",
                    auth.passwordSet() ? "Logga in först" : "Fel token – samma som PLUGGIS_DEVICE_TOKEN i .env"));
        }
        boolean change = auth.passwordSet();
        try {
            auth.setPassword(body.password());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        log.info("Lösenord för föräldravyn {} från {}", change ? "bytt" : "valt", req.getRemoteAddr());
        setCookie(req, res, auth.newSession(), AdminAuth.TTL);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String ip = req.getRemoteAddr();
        if (!guard.allow(ip)) {
            return ResponseEntity.status(429).body(Map.of("error", "För många försök – vänta en stund"));
        }
        if (!auth.check(body.password())) {
            guard.failure(ip);
            log.warn("Fel lösenord till föräldravyn från {}", ip);
            return ResponseEntity.status(401).body(Map.of("error", "Fel lösenord"));
        }
        setCookie(req, res, auth.newSession(), AdminAuth.TTL);
        log.info("Inloggad i föräldravyn från {}", ip);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
        setCookie(req, res, "", Duration.ZERO);
        return Map.of("ok", true);
    }

    /** Alla webbläsare och telefoner loggas ut (t.ex. om en telefon kommit bort). */
    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(HttpServletRequest req, HttpServletResponse res) {
        if (!loggedIn(req)) {
            return ResponseEntity.status(401).body(Map.of("error", "Logga in först"));
        }
        auth.logoutEverywhere();
        setCookie(req, res, "", Duration.ZERO);
        log.info("Föräldravyn: utloggad på alla enheter (från {})", req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private boolean loggedIn(HttpServletRequest req) {
        return (props.tokenRequired() && props.tokenMatches(req.getHeader(ApiTokenFilter.HEADER))) || hasValidCookie(req, auth);
    }

    static boolean hasValidCookie(HttpServletRequest req, AdminAuth auth) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie c : cookies) {
            if (AdminAuth.COOKIE.equals(c.getName()) && auth.valid(c.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static void setCookie(HttpServletRequest req, HttpServletResponse res, String value, Duration maxAge) {
        res.addHeader("Set-Cookie", ResponseCookie.from(AdminAuth.COOKIE, value)
                .httpOnly(true).secure(req.isSecure()).sameSite("Strict").path("/").maxAge(maxAge).build().toString());
    }
}
