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
 * Inloggning till föräldravyn (/auth, utanför /api). Lösenordet sätts första gången med PLUGGIS_ADMIN_PASSWORD
 * i .env (så att ingen annan på nätet hinner före) och kan sedan bytas när man är inloggad.
 */
@RestController
@RequestMapping("/auth")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final AdminAuth auth;
    private final LoginGuard guard = new LoginGuard(10, Duration.ofMinutes(10));

    public AdminAuthController(AdminAuth auth) {
        this.auth = auth;
    }

    public record PasswordRequest(String password) {
    }

    public record LoginRequest(String password) {
    }

    /** Är lösenord valt, och är den här webbläsaren inloggad (cookie eller token)? */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return Map.of("passwordSet", auth.passwordSet(), "loggedIn", loggedIn(req));
    }

    /** Byt lösenord (inloggad). Det första lösenordet sätts med PLUGGIS_ADMIN_PASSWORD i .env. */
    @PostMapping("/password")
    public ResponseEntity<?> setPassword(@RequestBody PasswordRequest body, HttpServletRequest req, HttpServletResponse res) {
        if (!loggedIn(req)) {
            log.warn("Byte av lösenord för föräldravyn nekat från {}: inte inloggad", req.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "Logga in först"));
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
        return hasValidCookie(req, auth);
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
