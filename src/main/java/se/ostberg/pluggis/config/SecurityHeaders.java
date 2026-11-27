package se.ostberg.pluggis.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Vanliga säkerhetshuvuden på alla svar: sidorna får inte bäddas in på andra sajter, webbläsaren gissar inte
 * filtyper, och adressen (med elevlänk) skickas inte vidare till andra sajter. Över https även HSTS.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeaders extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Permissions-Policy", "camera=(self), microphone=(self), geolocation=()");
        if (req.isSecure()) {
            res.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
        chain.doFilter(req, res);
    }
}
