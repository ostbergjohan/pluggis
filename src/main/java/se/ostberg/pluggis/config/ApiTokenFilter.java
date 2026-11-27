package se.ostberg.pluggis.config;

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
 * Vem som får använda /api/**: föräldern när den är inloggad i föräldravyn (cookie från /auth/login), eller eleven
 * med sin kod (header X-Pluggis-Token) – bara det eleven behöver, och bara sin egen data. Inget annat släpps in.
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Pluggis-Token";

    private final PluggisProperties props;
    private final se.ostberg.pluggis.chat.ChatAccess chat;
    private final se.ostberg.pluggis.student.StudentService students;
    private final LoginGuard guard = new LoginGuard(20, java.time.Duration.ofMinutes(10));
    private AdminAuth adminAuth;

    /** Inloggad förälder (cookie från /auth/login) räknas som token. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAdminAuth(AdminAuth adminAuth) {
        this.adminAuth = adminAuth;
    }

    public ApiTokenFilter(PluggisProperties props, se.ostberg.pluggis.chat.ChatAccess chat,
                          se.ostberg.pluggis.student.StudentService students) {
        this.props = props;
        this.chat = chat;
        this.students = students;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        Long student;
        if (adminAuth != null && AdminAuthController.hasValidCookie(request, adminAuth)) {
            student = parentStudent(request);                       // föräldern: vald elev
        } else {
            var kid = chat.studentFor(token);
            if (kid.isPresent()) {
                // Eleven (kod eller elevlänk): bara det eleven behöver, och bara sin egen data
                if (!se.ostberg.pluggis.chat.ChatAccess.allowed(request.getMethod(), request.getRequestURI())) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Inte med elevkoden");
                    return;
                }
                student = kid.get();
                request.setAttribute(STUDENT_ATTR, student);
                // Eleven kan bara vara sin egen enhet (poäng, kort, pass) – inte ett syskons
                String own = students.byId(student).map(se.ostberg.pluggis.student.Student::getDeviceId).orElse(null);
                var m = DEVICE_PATH.matcher(request.getRequestURI());
                if (own != null && m.find() && !m.group(1).equals(own)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Inte med elevkoden");
                    return;
                }
                if (own != null) {
                    request = ownDevice(request, own);
                }
            } else {
                if (!guard.allow(request.getRemoteAddr())) {
                    response.sendError(429, "För många felaktiga försök – vänta en stund");
                    return;
                }
                guard.failure(request.getRemoteAddr());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Fel eller saknad kod");
                return;
            }
        }
        se.ostberg.pluggis.student.StudentContext.set(student);
        try {
            chain.doFilter(request, response);
        } finally {
            se.ostberg.pluggis.student.StudentContext.clear();
        }
    }

    private static final java.util.regex.Pattern DEVICE_PATH = java.util.regex.Pattern.compile("^/api/devices/([^/]+)/");

    /** ?deviceId= blir alltid elevens egen. */
    private static HttpServletRequest ownDevice(HttpServletRequest request, String own) {
        return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public String getParameter(String name) {
                return "deviceId".equals(name) && super.getParameter(name) != null ? own : super.getParameter(name);
            }

            @Override
            public String[] getParameterValues(String name) {
                return "deviceId".equals(name) && super.getParameter(name) != null ? new String[]{own} : super.getParameterValues(name);
            }

            @Override
            public java.util.Map<String, String[]> getParameterMap() {
                java.util.Map<String, String[]> map = new java.util.LinkedHashMap<>(super.getParameterMap());
                map.computeIfPresent("deviceId", (k, v) -> new String[]{own});
                return java.util.Collections.unmodifiableMap(map);
            }
        };
    }

    /** Elevens id när eleven själv anropar (kod). Annars null. */
    public static final String STUDENT_ATTR = "pluggis.chatStudent";
    public static final String STUDENT_HEADER = "X-Pluggis-Student";

    /** Föräldern väljer elev med X-Pluggis-Student; iOS-genvägen m.fl. med ?deviceId=. Annars första eleven. */
    private Long parentStudent(HttpServletRequest request) {
        String header = request.getHeader(STUDENT_HEADER);
        if (header != null && !header.isBlank()) {
            try {
                Long id = Long.valueOf(header.trim());
                if (students.byId(id).isPresent()) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // ogiltigt – ta förvald
            }
        }
        String deviceId = request.getParameter("deviceId");
        if (deviceId != null) {
            var byDevice = students.byDeviceId(deviceId);
            if (byDevice.isPresent()) {
                return byDevice.get().getId();
            }
        }
        return se.ostberg.pluggis.student.StudentContext.defaultId();
    }
}
