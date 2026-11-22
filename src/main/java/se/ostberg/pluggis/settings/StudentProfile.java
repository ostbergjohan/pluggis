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

    private final se.ostberg.pluggis.student.StudentService students;

    public StudentProfile(se.ostberg.pluggis.student.StudentService students) {
        this.students = students;
    }

    /** Gäller aktuell elev (se StudentContext). */
    public String name() {
        return students.current().getName();
    }

    public String tone() {
        String t = students.current().getTone();
        return t != null && TONES.containsKey(t) ? t : "lugn";
    }

    public String extra() {
        return students.current().getExtra();
    }

    public void update(String name, String tone, String extra) {
        var s = students.current();
        if (name != null) {
            s.setName(clean(name, 40));
        }
        if (tone != null) {
            s.setTone(TONES.containsKey(tone) ? tone : "lugn");
        }
        if (extra != null) {
            s.setExtra(clean(extra, MAX_EXTRA));
        }
        students.save(s);
    }

    /** Texten som läggs efter coachens regler. */
    public String promptSection() {
        StringBuilder sb = new StringBuilder("\n\n## Eleven och tonen\n");
        String n = name();
        if (!n.isBlank()) {
            sb.append("Eleven heter ").append(n).append(". Använd namnet ibland, inte i varje mening.\n");
        }
        sb.append("Ton: ").append(TONES.get(tone()).instruction()).append('\n');
        var st = students.current();
        sb.append("Elevens pluggtid (om-så-plan): ").append(st.getStudyCue().isBlank() ? "" : st.getStudyCue() + ", ")
          .append("ca ").append(st.studyTimeOrDefault())
          .append(". Påminn gärna om den när ni pratar om när det ska göras.\n");
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
