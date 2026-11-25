package se.ostberg.pluggis.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import se.ostberg.pluggis.student.StudentContext;

/**
 * Ger genererade övningar variation: kommer ihåg vad som valdes de senaste gångerna (per elev, läxa och sort),
 * så att Claude kan väljas bort från samma sak igen, och ger ett slumpat fokus i materialet.
 */
@Component
public class Variety {

    private static final int KEEP = 12;
    private final Map<String, Deque<String>> recent = new ConcurrentHashMap<>();

    private static String key(String kind, Long homeworkId) {
        return StudentContext.currentOrDefault() + ":" + kind + ":" + homeworkId;
    }

    /** Det som valts de senaste gångerna, nyast först. */
    public List<String> recent(String kind, Long homeworkId) {
        Deque<String> d = recent.get(key(kind, homeworkId));
        if (d == null) {
            return List.of();
        }
        synchronized (d) {
            return new ArrayList<>(d);
        }
    }

    public void remember(String kind, Long homeworkId, Collection<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Deque<String> d = recent.computeIfAbsent(key(kind, homeworkId), k -> new ArrayDeque<>());
        synchronized (d) {
            for (String s : items) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                d.remove(s.trim());
                d.addFirst(s.trim());
            }
            while (d.size() > KEEP) {
                d.removeLast();
            }
        }
    }

    /** En instruktion om var i materialet tyngdpunkten ska ligga den här gången (olika varje gång). */
    public String focusHint() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int parts = 3;
        int part = r.nextInt(parts) + 1;
        String[] angles = {"orsaker och samband", "begrepp och vad de betyder", "processer och steg", "exempel och jämförelser",
                "det som är lätt att blanda ihop", "detaljer som ofta kommer på prov"};
        return "Variation " + r.nextInt(1000) + ": lägg tyngdpunkten på del " + part + " av " + parts
                + " i materialet (räknat från början) och gärna på " + angles[r.nextInt(angles.length)]
                + ", men håll dig till materialet. Välj inte alltid det första eller mest uppenbara.";
    }
}
