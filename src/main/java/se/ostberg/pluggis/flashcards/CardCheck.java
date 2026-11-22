package se.ostberg.pluggis.flashcards;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hittar flashkort där frågan avslöjar svaret, t.ex. "Vad kallas det gröna färgämnet klorofyll?" → "Klorofyll".
 * Jämför ordstammar (de första tecknen i varje innehållsord), så att "fotosyntesen" i frågan räknas som
 * "Fotosyntes" i svaret. Glosor på olika språk ("hund" → "dog") påverkas inte.
 */
public final class CardCheck {

    private static final Set<String> STOP = Set.of(
            "och", "eller", "att", "det", "den", "de", "en", "ett", "som", "är", "var", "har", "hade", "med",
            "för", "från", "till", "på", "av", "om", "i", "ur", "vid", "när", "hur", "vad", "vem", "vilken",
            "vilket", "vilka", "varför", "kallas", "heter", "betyder", "the", "and", "for", "with", "that", "this");

    private CardCheck() {
    }

    /** Avslöjar frågan (eller ledtråden) svaret? */
    public static boolean leaks(String question, String answer) {
        if (question == null || answer == null) {
            return false;
        }
        String q = normalize(question);
        String a = normalize(answer);
        if (a.length() < 3 || q.isEmpty()) {
            return false;
        }
        if ((" " + q + " ").contains(" " + a + " ")) {
            return true;
        }
        List<String> answerStems = stems(a);
        if (answerStems.isEmpty()) {
            return false;
        }
        List<String> questionStems = stems(q);
        long found = answerStems.stream().filter(questionStems::contains).count();
        // Ett innehållsord i svaret: räcker att det finns i frågan. Fler: minst hälften.
        return answerStems.size() == 1 ? found == 1 : found * 2 >= answerStems.size();
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        return n.replaceAll("[^\\p{L}\\p{N} ]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** Ordstammar för innehållsord (minst 4 bokstäver, inte småord): de första 5 tecknen. */
    static List<String> stems(String normalized) {
        List<String> out = new ArrayList<>();
        for (String w : normalized.split(" ")) {
            if (w.length() >= 4 && !STOP.contains(w) && !w.chars().allMatch(Character::isDigit)) {
                out.add(w.length() > 5 ? w.substring(0, 5) : w);
            }
        }
        return out;
    }
}
