package se.ostberg.pluggis.speech;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gör text uppläsbar innan den skickas till text till tal: datum, veckor, sidor, klockslag och
 * vanliga förkortningar blir ord, och markdown/emojis tas bort.
 * <p>
 * Försiktig med "3/4": blir bara ett datum när det står efter t.ex. en veckodag, "den", "till" eller "prov",
 * så att bråk i matteuppgifter fortsätter att vara bråk.
 */
public final class SpokenText {

    private static final Locale SV = Locale.forLanguageTag("sv-SE");
    private static final String[] MONTHS = {"januari", "februari", "mars", "april", "maj", "juni", "juli",
            "augusti", "september", "oktober", "november", "december"};

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final String DATE_WORDS = "(måndag|tisdag|onsdag|torsdag|fredag|lördag|söndag|mån|tis|ons|tors|tor|fre|lör|sön"
            + "|den|till|på|från|senast|före|efter|innan|tom|t\\.o\\.m\\.?|prov|provet|förhör|förhöret|inlämning|datum)";
    private static final Pattern DAY_MONTH = Pattern.compile("(?iu)\\b" + DATE_WORDS + "\\s+(\\d{1,2})/(\\d{1,2})\\b(?!/)");
    private static final Pattern WEEK = Pattern.compile("(?iu)\\bv(?:ecka)?\\.?\\s?(\\d{1,2})\\b");
    private static final Pattern PAGES = Pattern.compile("(?iu)\\b(?:s|sid)\\.\\s?(\\d+)\\s?[–—-]\\s?(\\d+)\\b");
    private static final Pattern PAGE = Pattern.compile("(?iu)\\b(?:s|sid)\\.\\s?(\\d+)\\b");
    private static final Pattern TIME = Pattern.compile("(?iu)\\b(?:kl\\.?\\s?)?([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b(?!\\s?%)");
    private static final Pattern KL = Pattern.compile("(?iu)\\bkl\\.\\s?");
    private static final Pattern RANGE = Pattern.compile("(\\d)\\s?[–—]\\s?(\\d)");
    private static final Pattern MARKUP = Pattern.compile("[*_#`>]+|\\p{So}|\\p{Cs}");

    private SpokenText() {
    }

    public static String forSpeech(String text) {
        return forSpeech(text, LocalDate.now());
    }

    static String forSpeech(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = text;
        s = replace(ISO_DATE, s, m -> {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            if (mo < 1 || mo > 12 || d < 1 || d > 31) {
                return m.group();
            }
            return d + " " + MONTHS[mo - 1] + (y == today.getYear() ? "" : " " + y);
        });
        s = replace(DAY_MONTH, s, m -> {
            int d = Integer.parseInt(m.group(2));
            int mo = Integer.parseInt(m.group(3));
            if (mo < 1 || mo > 12 || d < 1 || d > 31) {
                return m.group();
            }
            return m.group(1) + " " + d + " " + MONTHS[mo - 1];
        });
        s = replace(WEEK, s, m -> "vecka " + m.group(1));
        s = replace(PAGES, s, m -> "sidorna " + m.group(1) + " till " + m.group(2));
        s = replace(PAGE, s, m -> "sidan " + m.group(1));
        s = replace(TIME, s, m -> {
            if (!m.group().toLowerCase(SV).startsWith("kl") && !m.group().contains(":")) {
                return m.group(); // "3.5" är ett decimaltal, inte en tid
            }
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            return "klockan " + h + (min == 0 ? "" : " och " + min);
        });
        s = KL.matcher(s).replaceAll("klockan ");
        s = RANGE.matcher(s).replaceAll("$1 till $2");
        s = s.replaceAll("(?iu)\\bt\\.ex\\.?", "till exempel")
                .replaceAll("(?iu)\\bbl\\.a\\.?", "bland annat")
                .replaceAll("(?iu)\\bosv\\.?", "och så vidare")
                .replaceAll("(?iu)\\bdvs\\.?", "det vill säga")
                .replaceAll("(?iu)\\bca\\.?(?=\\s\\d)", "cirka")
                .replaceAll("(?iu)\\bkap\\.\\s?(?=\\d)", "kapitel ")
                .replaceAll("(?iu)\\buppg\\.\\s?(?=\\d)", "uppgift ");
        s = MARKUP.matcher(s).replaceAll("");
        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static String replace(Pattern p, String s, java.util.function.Function<Matcher, String> f) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
