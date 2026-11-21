package se.ostberg.pluggis.support;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Små textverktyg för importerad text. */
public final class TextFix {

    private TextFix() {
    }

    /**
     * Rättar UTF-8 som felaktigt lästs som Latin-1, t.ex. "FÃ¶rmiddag" → "Förmiddag".
     * Lämnar texten orörd om den inte ser trasig ut eller inte går att rätta.
     */
    public static String fixMojibake(String s) {
        if (s == null || (s.indexOf('Ã') < 0 && s.indexOf('Â') < 0)) {
            return s;
        }
        String fixed = new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return fixed.indexOf('�') >= 0 ? s : fixed;
    }

    /** Jämförelsenyckel för titlar: rättad kodning, gemener, ett mellanslag. */
    public static String key(String s) {
        String f = fixMojibake(s);
        return f == null ? "" : f.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
