package se.ostberg.laxcoach.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enkel delad-hemlighet-kontroll för /api/**. Räcker på hemnätet/VPN.
 * Ska tjänsten exponeras publikt (t.ex. som MCP-connector) behövs riktig auth (OAuth).
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Laxcoach-Token";
    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);

    private final LaxcoachProperties props;

    public ApiTokenFilter(LaxcoachProperties props) {
        this.props = props;
        if (!props.tokenRequired()) {
            log.warn("laxcoach.device-token är inte satt – API:t är öppet. Sätt LAXCOACH_DEVICE_TOKEN utanför lokal utveckling.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!props.tokenMatches(request.getHeader(HEADER))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Fel eller saknad token");
            return;
        }
        chain.doFilter(request, response);
    }
}
