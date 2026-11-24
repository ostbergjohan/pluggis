package se.ostberg.pluggis.chat;

import org.springframework.stereotype.Service;
import se.ostberg.pluggis.settings.SettingsService;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Elevlänken: en egen token för elevchatten i mobilen, skild från föräldrarnas.
 * Med den når man bara det eleven behöver (chatta, se planen och läxorna, bocka av, ladda upp material)
 * – inte inställningar, poängbonus, utbetalningar, kalender eller loggar.
 */
@Service
public class ChatAccess {

    public static final String TOKEN_KEY = "chat.token";
    public static final String DEVICE_KEY = "chat.deviceId";

    /** Metod + sökväg som elevlänken får använda. */
    private static final Pattern[] ALLOWED = {
            Pattern.compile("GET /api/chat/me"),
            Pattern.compile("GET /api/plan/(today|upcoming|progress)"),
            Pattern.compile("POST /api/plan/items/\\d+/done"),
            Pattern.compile("GET /api/homework"),
            Pattern.compile("GET /api/homework/\\d+/material"),
            Pattern.compile("GET /api/homework/material/\\d+/image"),
            Pattern.compile("POST /api/homework/upload"),
            Pattern.compile("GET /api/devices/[^/]+/rewards"),
            Pattern.compile("GET /api/devices/[^/]+/rewards/events"),
            Pattern.compile("POST /api/speech/preview"),
            Pattern.compile("GET /api/flashcards/decks"),
            Pattern.compile("GET /api/flashcards/decks/\\d+/next"),
            Pattern.compile("GET /api/flashcards/decks/\\d+/cards"),
            Pattern.compile("POST /api/games/order"),
            Pattern.compile("POST /api/games/done"),
            Pattern.compile("POST /api/flashcards/cards/\\d+/grade"),
            Pattern.compile("POST /api/flashcards/quiz"),
            Pattern.compile("POST /api/explain/start"),
            Pattern.compile("POST /api/exam/start"),
            Pattern.compile("POST /api/exam/[0-9a-f-]{36}/submit"),
            Pattern.compile("POST /api/explain/[0-9a-f-]{36}/answer"),
    };

    private final SettingsService settings;
    private final se.ostberg.pluggis.student.StudentService students;
    private final SecureRandom random = new SecureRandom();

    public ChatAccess(SettingsService settings, se.ostberg.pluggis.student.StudentService students) {
        this.settings = settings;
        this.students = students;
    }

    /**
     * Elevens kod (5 tecken) → eleven. Den äldre långa elevlänken (#t=…) gäller fortfarande för första eleven.
     */
    public Optional<Long> studentFor(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<Long> byCode = students.byCode(token).map(se.ostberg.pluggis.student.Student::getId);
        if (byCode.isPresent()) {
            return byCode;
        }
        return matches(token) ? Optional.of(se.ostberg.pluggis.student.StudentContext.defaultId()) : Optional.empty();
    }

    public Optional<String> token() {
        return settings.get(TOKEN_KEY).filter(t -> !t.isBlank());
    }

    /** Ny token – den gamla länken slutar fungera. */
    public String rotate() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String t = HexFormat.of().formatHex(b);
        settings.put(TOKEN_KEY, t);
        return t;
    }

    public void disable() {
        settings.put(TOKEN_KEY, "");
    }

    /** Vilken enhet chatten pratar som – samma som Tab5, så att pass, poäng och samtal hänger ihop. */
    public String deviceId(String fallback) {
        return settings.get(DEVICE_KEY).filter(d -> !d.isBlank()).orElse(fallback);
    }

    public void setDeviceId(String deviceId) {
        settings.put(DEVICE_KEY, deviceId == null ? "" : deviceId.trim());
    }

    public boolean matches(String candidate) {
        return candidate != null && token()
                .map(t -> MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8)))
                .orElse(false);
    }

    public static boolean allowed(String method, String path) {
        String key = method + " " + path;
        for (Pattern p : ALLOWED) {
            if (p.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }
}
