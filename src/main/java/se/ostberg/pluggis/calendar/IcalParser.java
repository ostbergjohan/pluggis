package se.ostberg.pluggis.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minimal iCalendar-läsare (RFC 5545) för skolplattformars läxkalendrar.
 * Läser VEVENT och VTODO: UID, SUMMARY, DESCRIPTION, DTSTART/DUE. Inget beroende behövs.
 */
public final class IcalParser {

    public record Entry(String uid, String summary, String description, LocalDate date) {
    }

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter UTC_DATETIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final Pattern TEST_WORDS = Pattern.compile(
            "\\b(prov|provet|förhör|läxförhör|glosförhör|diagnos|test|tentamen|nationella)\\b|glosförhör",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private IcalParser() {
    }

    public static List<Entry> parse(String ics, ZoneId zone) {
        List<Entry> out = new ArrayList<>();
        String uid = null, summary = null, description = null;
        LocalDate start = null, due = null;
        boolean inItem = false;

        for (String line : unfold(ics)) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.equals("BEGIN:VEVENT") || upper.equals("BEGIN:VTODO")) {
                inItem = true;
                uid = summary = description = null;
                start = due = null;
                continue;
            }
            if (upper.equals("END:VEVENT") || upper.equals("END:VTODO")) {
                LocalDate date = due != null ? due : start;
                if (inItem && uid != null && summary != null && date != null) {
                    out.add(new Entry(uid, summary, description, date));
                }
                inItem = false;
                continue;
            }
            if (!inItem) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon);
            int semi = name.indexOf(';');
            String key = (semi >= 0 ? name.substring(0, semi) : name).toUpperCase(Locale.ROOT);
            String value = line.substring(colon + 1);

            switch (key) {
                case "UID" -> uid = value.trim();
                case "SUMMARY" -> summary = unescape(value).trim();
                case "DESCRIPTION" -> description = unescape(value).trim();
                case "DTSTART" -> start = date(value, zone);
                case "DUE" -> due = date(value, zone);
                default -> { }
            }
        }
        return out;
    }

    /** Gissar om posten är ett prov/förhör utifrån texten. */
    public static boolean looksLikeTest(String summary, String description) {
        String text = (summary == null ? "" : summary) + " " + (description == null ? "" : description);
        return TEST_WORDS.matcher(text).find();
    }

    private static final Pattern SCHOOLWORK_WORDS = Pattern.compile(
            "läxa|läxor|läxförhör|inlämning|lämna in|uppgift|redovisning|glosor|glosförhör|förhör|prov|diagnos|test|tentamen|presentation|essä|labbrapport",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Ser posten ut som skolarbete (läxa, inlämning, prov …) och inte som t.ex. studiedag eller lov? */
    public static boolean looksLikeSchoolwork(String summary, String description) {
        String text = (summary == null ? "" : summary) + " " + (description == null ? "" : description);
        return SCHOOLWORK_WORDS.matcher(text).find();
    }

    /** "Matematik: Läxa kap 3" eller "EN - Glosor" → ämnet före avgränsaren, annars null. */
    public static String subjectFrom(String summary) {
        if (summary == null) {
            return null;
        }
        for (String sep : new String[]{":", " – ", " - "}) {
            int i = summary.indexOf(sep);
            if (i > 0 && i <= 25) {
                return summary.substring(0, i).trim();
            }
        }
        return null;
    }

    static LocalDate date(String value, ZoneId zone) {
        String v = value.trim();
        try {
            if (v.endsWith("Z") && v.length() >= 16) {
                Instant instant = java.time.LocalDateTime.parse(v, UTC_DATETIME).toInstant(java.time.ZoneOffset.UTC);
                return instant.atZone(zone).toLocalDate();
            }
            return LocalDate.parse(v.substring(0, Math.min(8, v.length())), BASIC_DATE);
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }

    static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        if (ics == null) {
            return lines;
        }
        StringBuilder current = null;
        for (String raw : ics.split("\r?\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && current != null) {
                current.append(raw, 1, raw.length());
            } else {
                if (current != null) {
                    lines.add(current.toString());
                }
                current = new StringBuilder(raw);
            }
        }
        if (current != null) {
            lines.add(current.toString());
        }
        return lines;
    }

    static String unescape(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                switch (n) {
                    case 'n', 'N' -> sb.append('\n');
                    default -> sb.append(n); // \, \; \\
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
