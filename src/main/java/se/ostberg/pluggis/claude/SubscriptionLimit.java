package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Läget för Claude-abonnemanget (Claude Code), så långt Claude Code berättar det: varje svar har
 * {@code rate_limit_event}-rader med status, hur mycket som gått åt och när gränsen släpper. När gränsen är nådd
 * sparas när den släpper, så att eleven får veta det ("Det går igen kl 15:00") och föräldravyn kan visa det.
 */
public final class SubscriptionLimit {

    /** Gränsen är nådd: meddelandet går att visa för eleven som det är. */
    public static class Reached extends IllegalStateException {
        public Reached(String message) {
            super(message);
        }
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    private static final Pattern EPOCH = Pattern.compile("\\|(\\d{10})\\b");
    private static final Pattern RESETS = Pattern.compile("resets?\\s+(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);

    private static volatile String status = "unknown";   // allowed, allowed_warning, rejected
    private static volatile String type;                  // five_hour, seven_day …
    private static volatile Double utilization;           // 0–1 om Claude Code skickar det
    private static volatile Instant resetsAt;
    private static volatile Instant limitedAt;
    private static volatile Instant updatedAt;

    private SubscriptionLimit() {
    }

    /** En {@code rate_limit_event}-rad från Claude Code. */
    static void update(JsonNode event) {
        JsonNode info = event.has("rate_limit_info") ? event.path("rate_limit_info") : event;
        String s = info.path("status").asText("");
        if (!s.isBlank()) {
            status = s;
        }
        if (info.hasNonNull("rateLimitType")) {
            type = info.path("rateLimitType").asText();
        }
        if (info.hasNonNull("utilization")) {
            utilization = info.path("utilization").asDouble();
        }
        Instant r = epoch(info.path("resetsAt"));
        if (r != null) {
            resetsAt = r;
        }
        updatedAt = Instant.now();
        if ("rejected".equals(s)) {
            limitedAt = Instant.now();
        } else if (s.startsWith("allowed")) {
            limitedAt = null;
        }
    }

    /** Ett svar som lyckades: gränsen är inte nådd (längre). */
    static void ok() {
        if (limitedAt != null) {
            limitedAt = null;
            status = "allowed";
        }
        updatedAt = Instant.now();
    }

    /** Gränsen är nådd. {@code text} är Claude Codes eget svar (kan innehålla när den släpper). */
    static Reached reached(String text) {
        status = "rejected";
        limitedAt = Instant.now();
        updatedAt = limitedAt;
        Instant r = parseReset(text, Instant.now());
        if (r != null) {
            resetsAt = r;
        }
        return new Reached(message());
    }

    static Instant parseReset(String text, Instant now) {
        if (text == null) {
            return null;
        }
        Matcher e = EPOCH.matcher(text);
        if (e.find()) {
            return Instant.ofEpochSecond(Long.parseLong(e.group(1)));
        }
        Matcher m = RESETS.matcher(text);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String ap = m.group(3);
            if (ap != null) {
                h = h % 12 + (ap.equalsIgnoreCase("pm") ? 12 : 0);
            }
            if (h > 23 || min > 59) {
                return null;
            }
            ZonedDateTime local = ZonedDateTime.of(LocalDate.ofInstant(now, ZONE), LocalTime.of(h, min), ZONE);
            if (!local.toInstant().isAfter(now)) {
                local = local.plusDays(1);
            }
            return local.toInstant();
        }
        return null;
    }

    private static Instant epoch(JsonNode n) {
        if (n == null || !n.isNumber()) {
            return null;
        }
        long v = n.asLong();
        return v > 100_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
    }

    /** Är gränsen nådd just nu (och inte redan släppt)? */
    public static boolean reachedNow() {
        Instant l = limitedAt;
        if (l == null) {
            return false;
        }
        Instant r = resetsAt;
        return r == null ? l.isAfter(Instant.now().minusSeconds(30 * 60)) : r.isAfter(Instant.now());
    }

    /** Till eleven (svenska eller engelska efter elevens språk). */
    public static String message() {
        boolean en = se.ostberg.pluggis.student.StudentLanguage.english();
        Instant r = resetsAt;
        String when = null;
        if (r != null && r.isAfter(Instant.now())) {
            ZonedDateTime z = r.atZone(ZONE);
            boolean today = z.toLocalDate().equals(LocalDate.now(ZONE));
            String time = z.format(DateTimeFormatter.ofPattern("HH:mm"));
            when = today ? (en ? "at " + time : "kl " + time)
                    : (en ? z.format(DateTimeFormatter.ofPattern("EEEE", java.util.Locale.ENGLISH)) + " at " + time
                          : z.format(DateTimeFormatter.ofPattern("EEEE", Locale.SV)) + " kl " + time);
        }
        if (en) {
            return "Pluggis has used up its Claude time for now" + (when == null ? " – try again a bit later." : " – it works again " + when + ".");
        }
        return "Pluggis har använt upp sin Claude-tid för en stund" + (when == null ? " – försök igen lite senare." : " – det går igen " + when + ".");
    }

    /** För föräldravyn. */
    public static Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("reached", reachedNow());
        m.put("type", type);
        m.put("utilization", utilization);
        m.put("resetsAt", resetsAt == null ? null : resetsAt.toString());
        m.put("limitedAt", limitedAt == null ? null : limitedAt.toString());
        m.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        m.put("message", reachedNow() ? message() : null);
        return m;
    }

    /** Om felet (eller något det orsakades av) är att gränsen är nådd: meddelandet till eleven, annars null. */
    public static String userMessage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof Reached) {
                return t.getMessage();
            }
        }
        return null;
    }

    private static final class Locale {
        static final java.util.Locale SV = java.util.Locale.forLanguageTag("sv-SE");
    }

    /** För test. */
    static void reset() {
        status = "unknown";
        type = null;
        utilization = null;
        resetsAt = null;
        limitedAt = null;
        updatedAt = null;
    }
}
