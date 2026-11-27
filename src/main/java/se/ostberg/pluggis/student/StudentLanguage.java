package se.ostberg.pluggis.student;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Elevens språk ("sv" eller "en") utan databasanrop – Claude-anropen och rösten läser det för aktuell elev
 * ({@link StudentContext}). Fylls av {@link StudentService} när elever läses in och sparas. Svenska är standard.
 */
public final class StudentLanguage {

    public static final String SV = "sv";
    public static final String EN = "en";

    private static final Map<Long, String> BY_STUDENT = new ConcurrentHashMap<>();

    private StudentLanguage() {
    }

    static void remember(Student s) {
        if (s != null && s.getId() != null) {
            BY_STUDENT.put(s.getId(), s.getLanguage());
        }
    }

    static void forget(Long id) {
        if (id != null) {
            BY_STUDENT.remove(id);
        }
    }

    /** Språket för aktuell elev. */
    public static String current() {
        return BY_STUDENT.getOrDefault(StudentContext.currentOrDefault(), SV);
    }

    public static boolean english() {
        return EN.equals(current());
    }

    /** "en" → "en", allt annat → "sv". */
    public static String normalize(String lang) {
        return lang != null && lang.trim().toLowerCase(java.util.Locale.ROOT).startsWith("en") ? EN : SV;
    }
}
