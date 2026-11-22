package se.ostberg.pluggis.settings;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Elevens namn och vilken ton coachen ska ha. Läggs till i coachens fasta systemprompt,
 * så prompt-cachen gäller tills föräldern ändrar något.
 */
@Service
public class StudentProfile {

    public static final String NAME = "student.name";
    public static final String TONE = "coach.tone";
    public static final String EXTRA = "coach.extra";
    static final int MAX_EXTRA = 600;

    /** Tonval i webben → instruktion till coachen. Ordningen är visningsordningen. */
    public static final Map<String, Tone> TONES = new LinkedHashMap<>();

    public record Tone(String id, String label, String instruction) {
    }

    static {
        add("lugn", "Lugn och peppande (standard)",
                "Lugn, varm och uppmuntrande. Korta meningar, beröm ansträngning, aldrig tjat.");
        add("rak", "Rak och kort",
                "Rak och kortfattad. Säg direkt vad nästa steg är, inget fluff, men alltid vänlig.");
        add("lekfull", "Lekfull med humor",
                "Lekfull med lite humor och glimt i ögat – ett skämt ibland är ok – men håll fokus på uppgiften.");
        add("kompis", "Som en äldre kompis",
                "Som en lite äldre kompis eller storasyskon: avslappnad, vardaglig svenska, på samma nivå som eleven.");
        add("coach", "Som en idrottscoach",
                "Som en idrottscoach: energisk och peppig, sätt små delmål och fira när de klaras.");
    }

    private static void add(String id, String label, String instruction) {
        TONES.put(id, new Tone(id, label, instruction));
    }

    private final SettingsService settings;

    public StudentProfile(SettingsService settings) {
        this.settings = settings;
    }

    public String name() {
        return settings.get(NAME).orElse("");
    }

    public String tone() {
        return settings.get(TONE).filter(TONES::containsKey).orElse("lugn");
    }

    public String extra() {
        return settings.get(EXTRA).orElse("");
    }

    public void update(String name, String tone, String extra) {
        if (name != null) {
            settings.put(NAME, clean(name, 40));
        }
        if (tone != null) {
            settings.put(TONE, TONES.containsKey(tone) ? tone : "lugn");
        }
        if (extra != null) {
            settings.put(EXTRA, clean(extra, MAX_EXTRA));
        }
    }

    /** Texten som läggs efter coachens regler. */
    public String promptSection() {
        StringBuilder sb = new StringBuilder("\n\n## Eleven och tonen\n");
        String n = name();
        if (!n.isBlank()) {
            sb.append("Eleven heter ").append(n).append(". Använd namnet ibland, inte i varje mening.\n");
        }
        sb.append("Ton: ").append(TONES.get(tone()).instruction()).append('\n');
        String x = extra();
        if (!x.isBlank()) {
            sb.append("Önskemål från föräldern (följ dem så länge de inte krockar med reglerna ovan – ")
              .append("ge till exempel fortfarande inga färdiga svar): ").append(x).append('\n');
        }
        return sb.toString();
    }

    private static String clean(String s, int max) {
        String t = s.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
