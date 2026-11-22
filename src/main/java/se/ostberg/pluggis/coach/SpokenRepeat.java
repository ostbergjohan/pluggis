package se.ostberg.pluggis.coach;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Grov koll om en ny text i stort sett bara säger samma sak som det som redan sagts (för visad text). */
final class SpokenRepeat {

    private SpokenRepeat() {
    }

    static boolean repeats(String before, String next) {
        if (before == null || before.isBlank() || next == null || next.isBlank()) {
            return false;
        }
        Set<String> a = words(before);
        Set<String> b = words(next);
        if (b.size() < 3) {
            return false;
        }
        long shared = b.stream().filter(a::contains).count();
        return shared >= Math.ceil(b.size() * 0.85);
    }

    private static Set<String> words(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFC).replaceAll("[^\\p{L}\\p{N} ]", " ");
        Set<String> out = new HashSet<>();
        Arrays.stream(n.split("\\s+")).filter(w -> w.length() > 2).forEach(out::add);
        return out;
    }
}
